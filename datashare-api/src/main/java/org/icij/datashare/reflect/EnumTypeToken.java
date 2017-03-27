package org.icij.datashare.reflect;

import java.util.Locale;
import java.util.Optional;

import static org.icij.datashare.function.Functions.capitalize;
import static org.icij.datashare.function.ThrowingFunctions.removePattFrom;

/**
 * Enum-based type token used for reflective instantiation in DataShare static factory methods
 *
 * Provides
 *  - the concrete class name from an interface and an enum value
 *  - the enum value          from a concrete class name
 *
 * Naming convention for concrete classes: {@code Enumvaluename + InterfaceName}
 *
 * Created by julien on 7/16/16.
 */
public interface EnumTypeToken {

    String getClassName();

    /**
     * Concrete class from (interface, enum value).
     *
     * @param interfaceClass the interface   whose name is the suffix of the concrete class to build
     * @param enumValue      the enum value  whose name is the prefix of the concrete class to build
     * @return the fully qualified name of the concrete class implementing interface
     */
    default String buildClassName(final Class<?> interfaceClass,
                                  final Enum<? extends EnumTypeToken> enumValue) {
        String packageName   = interfaceClass.getPackage().getName();
        String typeName      = enumValue.name().toLowerCase();
        String interfaceName = interfaceClass.getSimpleName();
        String implClassName = capitalize.apply(typeName) + interfaceName;
        return String.join(".", packageName, typeName, implClassName);
    }

    static <E extends Enum<E>> Optional<E> parseClassName(final Class<?> interfaceClass,
                                                          final Class<E> enumType,
                                                          final String className) {
        if (className == null || className.isEmpty()) {
            return Optional.empty();
        }
        String[] classNameSplit  = className.split("\\.");
        String   simpleClassName = classNameSplit[classNameSplit.length-1];
        String   interfaceName   = interfaceClass.getSimpleName();
        String   enumValueName   = removePattFrom.apply(interfaceName).apply(simpleClassName);
        return parse(enumType, enumValueName);
    }

    static <E extends Enum<E>> Optional<E> parse(final Class<E> enumType, final String enumValueName) {
        if (enumValueName == null || enumValueName.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of( Enum.valueOf( enumType, enumValueName.toUpperCase(Locale.ROOT) ) );

        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

}
