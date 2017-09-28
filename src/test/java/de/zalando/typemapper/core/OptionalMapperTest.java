package de.zalando.typemapper.core;

import com.google.common.base.Optional;
import org.junit.Test;

import javax.annotation.Nullable;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OptionalMapperTest {

    @Test
    public void testShouldWorkWithOptional() {
        for (Integer object: Arrays.asList(null, Integer.valueOf(42))) {
            testOptional(object);
            testGuavaOptional(object);
        }
        for (String object: Arrays.asList(null, "Don't panic!")) {
            testOptional(object);
            testGuavaOptional(object);
        }
        for (FooBar object: Arrays.asList(null, new FooBar())) {
            testOptional(object);
            testGuavaOptional(object);
        }
    }

    private <T> void testOptional(@Nullable T object) {
        java.util.Optional<T> expected = java.util.Optional.ofNullable(object);
        assertTrue(OptionalMapper.isOptional(expected.getClass()));
        assertEquals(object, OptionalMapper.get(expected));

        Object actual = OptionalMapper.map(java.util.Optional.class, object);
        assertTrue(OptionalMapper.isOptional(actual.getClass()));
        assertEquals(object, OptionalMapper.get(actual));

        assertEquals(expected, actual);

        assertTrue(actual instanceof java.util.Optional);
        final java.util.Optional<?> actualOptional = (java.util.Optional<?>) actual;
        if (object == null) {
            assertFalse(actualOptional.isPresent());
        } else {
            assertTrue(actualOptional.isPresent());
            assertEquals(object, actualOptional.get());
        }
    }

    private <T> void testGuavaOptional(@Nullable T object) {
        Optional<T> expected = Optional.fromNullable(object);
        assertTrue(OptionalMapper.isOptional(expected.getClass()));
        assertEquals(object, OptionalMapper.get(expected));

        Object actual = OptionalMapper.map(Optional.class, object);
        assertTrue(OptionalMapper.isOptional(actual.getClass()));
        assertEquals(object, OptionalMapper.get(actual));

        assertEquals(expected, actual);

        assertTrue(actual instanceof Optional);
        final Optional<?> actualOptional = (Optional<?>) actual;
        if (object == null) {
            assertFalse(actualOptional.isPresent());
        } else {
            assertTrue(actualOptional.isPresent());
            assertEquals(object, actualOptional.get());
        }
    }

    @Test
    public void testShouldNotWrapIntoOptional() {
        for (Object object: Arrays.asList(null, Integer.valueOf(42), "Don't panic!", new FooBar())) {
            testNoOptional(object);
        }
    }

    private void testNoOptional(@Nullable Object object) {
        // Basically any class, except (java8's or Guava's) Optional.
        for (Class<?> klass: Arrays.asList(Integer.class, String.class, Object.class, FooBar.class)) {
            assertFalse(OptionalMapper.isOptional(klass));
            Object actual = OptionalMapper.map(klass, object);
            assertEquals(object, actual);
        }
    }

    static class FooBar { }
}
