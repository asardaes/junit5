/*
 * Copyright 2015-2018 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * http://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.platform.engine.support.descriptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.platform.commons.util.PreconditionViolationException;

/**
 * Unit tests for {@link PackageSource} and {@link MethodSource}.
 *
 * @since 1.0
 */
class GenericSourceTests extends AbstractTestSourceTests {

	@Override
	Stream<Serializable> createSerializableInstances() throws Exception {
		return Stream.of( //
			PackageSource.from("package.source"), //
			MethodSource.from(getMethod("method1")), //
			MethodSource.from(getMethod("method2")) //
		);
	}

	@Test
	void packageSourceFromNullPackageName() {
		assertThrows(PreconditionViolationException.class, () -> PackageSource.from((String) null));
	}

	@Test
	void packageSourceFromEmptyPackageName() {
		assertThrows(PreconditionViolationException.class, () -> PackageSource.from("  "));
	}

	@Test
	void packageSourceFromPackageName() {
		String testPackage = getClass().getPackage().getName();
		PackageSource source = PackageSource.from(testPackage);

		assertThat(source.getPackageName()).isEqualTo(testPackage);
	}

	@Test
	void packageSourceFromNullPackageReference() {
		assertThrows(PreconditionViolationException.class, () -> PackageSource.from((Package) null));
	}

	@Test
	void packageSourceFromPackageReference() {
		Package testPackage = getClass().getPackage();
		PackageSource source = PackageSource.from(testPackage);

		assertThat(source.getPackageName()).isEqualTo(testPackage.getName());
	}

	@Test
	void methodSource() throws Exception {
		Method testMethod = getMethod("method1");
		MethodSource source = MethodSource.from(testMethod);

		assertThat(source.getClassName()).isEqualTo(getClass().getName());
		assertThat(source.getMethodName()).isEqualTo(testMethod.getName());
		assertThat(source.getMethodParameterTypes()).isEqualTo(String.class.getName());
	}

	@Test
	void equalsAndHashCodeForPackageSource() {
		Package pkg1 = getClass().getPackage();
		Package pkg2 = String.class.getPackage();
		assertEqualsAndHashCode(PackageSource.from(pkg1), PackageSource.from(pkg1), PackageSource.from(pkg2));
	}

	@Test
	void equalsAndHashCodeForMethodSource(TestInfo testInfo) throws Exception {
		Method method1 = getMethod("method1");
		Method method2 = getMethod("method2");
		assertEqualsAndHashCode(MethodSource.from(method1), MethodSource.from(method1), MethodSource.from(method2));
	}

	private Method getMethod(String name) throws Exception {
		return getClass().getDeclaredMethod(name, String.class);
	}

	void method1(String text) {
	}

	void method2(String text) {
	}

}
