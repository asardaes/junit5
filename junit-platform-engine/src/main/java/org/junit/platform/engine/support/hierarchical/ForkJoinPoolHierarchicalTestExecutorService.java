/*
 * Copyright 2015-2023 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * https://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.platform.engine.support.hierarchical;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.apiguardian.api.API.Status.STABLE;
import static org.junit.platform.engine.support.hierarchical.Node.ExecutionMode.CONCURRENT;

import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.reflect.Constructor;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apiguardian.api.API;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.function.Try;
import org.junit.platform.commons.logging.LoggerFactory;
import org.junit.platform.commons.util.ExceptionUtils;
import org.junit.platform.engine.ConfigurationParameters;

/**
 * A {@link ForkJoinPool}-based
 * {@linkplain HierarchicalTestExecutorService executor service} that executes
 * {@linkplain TestTask test tasks} with the configured parallelism.
 *
 * @since 1.3
 * @see ForkJoinPool
 * @see DefaultParallelExecutionConfigurationStrategy
 */
@API(status = STABLE, since = "1.10")
public class ForkJoinPoolHierarchicalTestExecutorService implements HierarchicalTestExecutorService {

	private final TestTaskSubmitter testTaskSubmitter;

	/**
	 * Create a new {@code ForkJoinPoolHierarchicalTestExecutorService} based on
	 * the supplied {@link ConfigurationParameters}.
	 *
	 * @see DefaultParallelExecutionConfigurationStrategy
	 */
	public ForkJoinPoolHierarchicalTestExecutorService(ConfigurationParameters configurationParameters) {
		this(createConfiguration(configurationParameters));
	}

	/**
	 * Create a new {@code ForkJoinPoolHierarchicalTestExecutorService} based on
	 * the supplied {@link ParallelExecutionConfiguration}.
	 *
	 * @since 1.7
	 */
	@API(status = STABLE, since = "1.10")
	public ForkJoinPoolHierarchicalTestExecutorService(ParallelExecutionConfiguration configuration) {
		if (configuration.getTestExecutor() == ParallelExecutionConfiguration.TestExecutor.FORK_JOIN) {
			testTaskSubmitter = new ForkJoinPoolTestTaskSubmitter(configuration);
		}
		else {
			testTaskSubmitter = new FixedThreadPoolTestTaskSubmitter(configuration);
		}
	}

	private static ParallelExecutionConfiguration createConfiguration(ConfigurationParameters configurationParameters) {
		ParallelExecutionConfigurationStrategy strategy = DefaultParallelExecutionConfigurationStrategy.getStrategy(
			configurationParameters);
		return strategy.createConfiguration(configurationParameters);
	}

	private static ForkJoinPool createForkJoinPool(ParallelExecutionConfiguration configuration) {
		ForkJoinWorkerThreadFactory threadFactory = new WorkerThreadFactory();
		// Try to use constructor available in Java >= 9
		Callable<ForkJoinPool> constructorInvocation = sinceJava9Constructor() //
				.map(sinceJava9ConstructorInvocation(configuration, threadFactory))
				// Fallback for Java 8
				.orElse(sinceJava7ConstructorInvocation(configuration, threadFactory));
		return Try.call(constructorInvocation) //
				.getOrThrow(cause -> new JUnitException("Failed to create ForkJoinPool", cause));
	}

	private static Optional<Constructor<ForkJoinPool>> sinceJava9Constructor() {
		return Try.call(() -> ForkJoinPool.class.getDeclaredConstructor(Integer.TYPE, ForkJoinWorkerThreadFactory.class,
			UncaughtExceptionHandler.class, Boolean.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, Predicate.class,
			Long.TYPE, TimeUnit.class)) //
				.toOptional();
	}

	private static Function<Constructor<ForkJoinPool>, Callable<ForkJoinPool>> sinceJava9ConstructorInvocation(
			ParallelExecutionConfiguration configuration, ForkJoinWorkerThreadFactory threadFactory) {
		return constructor -> () -> constructor.newInstance(configuration.getParallelism(), threadFactory, null, false,
			configuration.getCorePoolSize(), configuration.getMaxPoolSize(), configuration.getMinimumRunnable(),
			configuration.getSaturatePredicate(), configuration.getKeepAliveSeconds(), TimeUnit.SECONDS);
	}

	private static Callable<ForkJoinPool> sinceJava7ConstructorInvocation(ParallelExecutionConfiguration configuration,
			ForkJoinWorkerThreadFactory threadFactory) {
		return () -> new ForkJoinPool(configuration.getParallelism(), threadFactory, null, false);
	}

	@SuppressWarnings("try")
	private static void executeWithLock(TestTask testTask) {
		try (ResourceLock lock = testTask.getResourceLock().acquire()) {
			testTask.execute();
		}
		catch (InterruptedException e) {
			ExceptionUtils.throwAsUncheckedException(e);
		}
	}

	@Override
	public Future<Void> submit(TestTask testTask) {
		return testTaskSubmitter.submit(testTask);
	}

	@Override
	public void invokeAll(List<? extends TestTask> tasks) {
		if (tasks.size() == 1) {
			testTaskSubmitter.submitManaged(tasks.get(0)).get();
			return;
		}
		Deque<ExclusiveTask> nonConcurrentTasks = new LinkedList<>();
		Deque<ExclusiveTask> concurrentTasksInReverseOrder = new LinkedList<>();
		forkConcurrentTasks(tasks, nonConcurrentTasks, concurrentTasksInReverseOrder);
		executeNonConcurrentTasks(nonConcurrentTasks);
		joinConcurrentTasksInReverseOrderToEnableWorkStealing(concurrentTasksInReverseOrder);
	}

	private void forkConcurrentTasks(List<? extends TestTask> tasks, Deque<ExclusiveTask> nonConcurrentTasks,
			Deque<ExclusiveTask> concurrentTasksInReverseOrder) {
		for (TestTask testTask : tasks) {
			ExclusiveTask exclusiveTask = new ExclusiveTask(testTask);
			if (testTask.getExecutionMode() == CONCURRENT) {
				concurrentTasksInReverseOrder.addFirst(testTaskSubmitter.submitConcurrent(exclusiveTask));
			}
			else {
				nonConcurrentTasks.add(exclusiveTask);
			}
		}
	}

	// TODO usually, this is already using a ForkJoinPool thread, but with 2 pools, we could exceed desired parallelism?
	@SuppressWarnings("try")
	private void executeNonConcurrentTasks(Deque<ExclusiveTask> nonConcurrentTasks) {
		for (ExclusiveTask task : nonConcurrentTasks) {
			testTaskSubmitter.invoke(task);
		}
	}

	private void joinConcurrentTasksInReverseOrderToEnableWorkStealing(
			Deque<ExclusiveTask> concurrentTasksInReverseOrder) {
		for (ExclusiveTask forkedTask : concurrentTasksInReverseOrder) {
			forkedTask.await();
		}
	}

	@Override
	public void close() {
		testTaskSubmitter.close();
	}

	private static class FixedThreadPoolTestTaskSubmitter extends ForkJoinPoolTestTaskSubmitter {
		private final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
		private final ExecutorService executorService;

		FixedThreadPoolTestTaskSubmitter(ParallelExecutionConfiguration configuration) {
			super(configuration);
			executorService = Executors.newFixedThreadPool(configuration.getParallelism(), (r) -> {
				Thread thread = new Thread(r);
				thread.setContextClassLoader(contextClassLoader);
				return thread;
			});
			LoggerFactory.getLogger(getClass()).config(
				() -> "Executing tests with FixedThreadPool with parallelism of " + configuration.getParallelism());
		}

		@Override
		public Future<Void> submit(TestTask testTask) {
			if (!testTask.getType().isTest()) {
				return super.submit(testTask);
			}
			else if (testTask.getExecutionMode() == CONCURRENT) {
				return executorService.submit(() -> executeWithLock(testTask), null);
			}
			else {
				// TODO what if this is called from a ForkJoinPool thread?
				executeWithLock(testTask);
				return completedFuture(null);
			}
		}

		@Override
		public ExclusiveTask submitConcurrent(ExclusiveTask exclusiveTask) {
			TestTask testTask = exclusiveTask.testTask;
			if (testTask.getType().isTest()) {
				return new FixedThreadPoolExclusiveTask(executorService.submit(() -> executeWithLock(testTask), null));
			}
			else {
				return super.submitConcurrent(exclusiveTask);
			}
		}

		@Override
		public void invoke(ExclusiveTask exclusiveTask) {
			if (exclusiveTask.testTask.getType().isTest()) {
				new ManagedFuture(executorService.submit(exclusiveTask::compute, null)).get();
			}
			else {
				super.invoke(exclusiveTask);
			}
		}

		@Override
		public void close() {
			super.close();
			executorService.shutdownNow();
		}
	}

	private static class ForkJoinPoolTestTaskSubmitter implements TestTaskSubmitter {

		private final ForkJoinPool forkJoinPool;
		private final int parallelism;

		private ForkJoinPoolTestTaskSubmitter(ParallelExecutionConfiguration configuration) {
			this.forkJoinPool = createForkJoinPool(configuration);
			parallelism = forkJoinPool.getParallelism();
			LoggerFactory.getLogger(getClass()).config(() -> "Using ForkJoinPool with parallelism of " + parallelism);
		}

		private boolean isAlreadyRunningInForkJoinPool() {
			return ForkJoinTask.getPool() == forkJoinPool;
		}

		@Override
		public Future<Void> submit(TestTask testTask) {
			ExclusiveTask exclusiveTask = new ExclusiveTask(testTask);
			if (!isAlreadyRunningInForkJoinPool()) {
				// ensure we're running inside the ForkJoinPool so we
				// can use ForkJoinTask API in invokeAll etc.
				return forkJoinPool.submit(exclusiveTask);
			}
			// Limit the amount of queued work so we don't consume dynamic tests too eagerly
			// by forking only if the current worker thread's queue length is below the
			// desired parallelism. This optimistically assumes that the already queued tasks
			// can be stolen by other workers and the new task requires about the same
			// execution time as the already queued tasks. If the other workers are busy,
			// the parallelism is already at its desired level. If all already queued tasks
			// can be stolen by otherwise idle workers and the new task takes significantly
			// longer, parallelism will drop. However, that only happens if the enclosing test
			// task is the only one remaining which should rarely be the case.
			if (testTask.getExecutionMode() == CONCURRENT && ForkJoinTask.getSurplusQueuedTaskCount() < parallelism) {
				return exclusiveTask.fork();
			}
			exclusiveTask.compute();
			return completedFuture(null);
		}

		@Override
		public ManagedFuture submitManaged(TestTask testTask) {
			return new ManagedFuture(submit(testTask));
		}

		@Override
		public ExclusiveTask submitConcurrent(ExclusiveTask exclusiveTask) {
			exclusiveTask.fork();
			return exclusiveTask;
		}

		@Override
		public void invoke(ExclusiveTask exclusiveTask) {
			exclusiveTask.compute();
		}

		@Override
		public void close() {
			forkJoinPool.shutdownNow();
		}
	}

	private interface TestTaskSubmitter {
		Future<Void> submit(TestTask testTask);

		ManagedFuture submitManaged(TestTask testTask);

		ExclusiveTask submitConcurrent(ExclusiveTask exclusiveTask);

		void invoke(ExclusiveTask exclusiveTask);

		void close();
	}

	private static class ManagedFuture {
		private final Future<Void> future;

		ManagedFuture(Future<Void> future) {
			this.future = future;
		}

		void get() {
			try {
				future.get();
			}
			catch (Exception e) {
				future.cancel(true);
				ExceptionUtils.throwAsUncheckedException(e);
			}
		}
	}

	@SuppressWarnings("serial")
	static class FixedThreadPoolExclusiveTask extends ExclusiveTask {
		private final Future<Void> future;

		FixedThreadPoolExclusiveTask(Future<Void> future) {
			super(null);
			this.future = future;
		}

		@Override
		public void compute() {
			try {
				future.get();
			}
			catch (Exception e) {
				future.cancel(true);
				ExceptionUtils.throwAsUncheckedException(e);
			}
		}

		@Override
		void await() {
			compute();
		}
	}

	// this class cannot not be serialized because TestTask is not Serializable
	@SuppressWarnings("serial")
	static class ExclusiveTask extends RecursiveAction {

		private final TestTask testTask;

		ExclusiveTask(TestTask testTask) {
			this.testTask = testTask;
		}

		@Override
		public void compute() {
			executeWithLock(testTask);
		}

		void await() {
			join();
		}

	}

	static class WorkerThreadFactory implements ForkJoinPool.ForkJoinWorkerThreadFactory {

		private final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();

		@Override
		public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
			return new WorkerThread(pool, contextClassLoader);
		}

	}

	static class WorkerThread extends ForkJoinWorkerThread {

		WorkerThread(ForkJoinPool pool, ClassLoader contextClassLoader) {
			super(pool);
			setContextClassLoader(contextClassLoader);
		}

	}

}
