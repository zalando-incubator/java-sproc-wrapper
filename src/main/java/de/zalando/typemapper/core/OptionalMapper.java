package de.zalando.typemapper.core;

import com.google.common.base.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class OptionalMapper {
    private static final Logger LOG = LoggerFactory.getLogger(OptionalMapper.class);

    private static final String JAVA_OPTIONAL_CLASS_NAME = "java.util.Optional";
    private static final Method Optional_ofNullable = getOptionalMethod("ofNullable", Object.class);
    private static final Method Optional_orElse = getOptionalMethod("orElse", Object.class);

    private static @Nullable Method getOptionalMethod(final String methodName, Class<?>... parameterTypes) {
        try {
            return Class.forName(JAVA_OPTIONAL_CLASS_NAME).getMethod(methodName, parameterTypes);
        } catch (ClassNotFoundException e) {
            LOG.warn("Java Optional is not supported", e);
        } catch (NoSuchMethodException e) {
            LOG.warn("Java Optional: dropping support", e);
        }
        return null;
    }

    private static boolean isJavaOptional(final Class<?> klass) {
        return Optional_ofNullable != null && JAVA_OPTIONAL_CLASS_NAME.equals(klass.getCanonicalName());
    }

    public static boolean isOptional(final Class<?> klass) {
        return Optional.class.isAssignableFrom(klass) || isJavaOptional(klass);
    }

    public static @Nullable Object map(final Class<?> klass, @Nullable final Object value) {
        if (Optional.class.isAssignableFrom(klass)) {
            return Optional.fromNullable(value);
        }
        if (isJavaOptional(klass)) {
            try {
                return Optional_ofNullable.invoke(null, value);
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException("Failed to wrap in Optional", e);
            } catch (InvocationTargetException e) {
                throw new IllegalArgumentException("Failed to wrap in Optional", e);
            }
        }
        return value;
    }

    public static @Nullable Object get(@Nullable final Object object) {
        if (object instanceof Optional) {
            return ((Optional<?>) object).orNull();
        }
        if (object != null && isJavaOptional(object.getClass())) {
            try {
                // NB: explicitly cast null to Object to avoid:
                //  java.lang.IllegalArgumentException: wrong number of arguments
                return Optional_orElse.invoke(object, (Object) null);
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException("Could not extract value from Optional", e);
            } catch (InvocationTargetException e) {
                throw new IllegalArgumentException("Could not extract value from Optional", e);
            }
        }
        return object;
    }
}
