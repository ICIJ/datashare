package org.icij.datashare.reflect;

import java.lang.reflect.*;
import java.util.*;

/**
 *
 *
 */
public class Reflections {

    /**
     * Displays the inheritance hierarchy for obj
     *
     * @param obj is the object to inspect
     */
    static void inspectObject(Object obj) {

        Type type = obj.getClass();
        while (type != null) {
            System.out.print(type + " implements");
            Class<?> rawType = (type instanceof ParameterizedType)
                    ? (Class<?>) ((ParameterizedType) type).getRawType()
                    : (Class<?>) type;
            Type[] interfaceTypes = rawType.getGenericInterfaces();

            if (interfaceTypes.length > 0) {

                System.out.println(":");
                for (Type interfaceType : interfaceTypes) {
                    if (interfaceType instanceof ParameterizedType) {
                        ParameterizedType parameterizedType = (ParameterizedType)interfaceType;
                        System.out.print("  " + parameterizedType.getRawType() + " with type args: ");
                        Type[] actualTypeArgs = parameterizedType.getActualTypeArguments();
                        System.out.println(Arrays.toString(actualTypeArgs));
                    }
                    else {
                        System.out.println("  " + interfaceType);
                    }
                }
            }
            else {
                System.out.println(" nothing");
            }
            type = rawType.getGenericSuperclass();
        }
    }


    /**
     * Get the underlying class for a type, or null if the type is a variable type.
     *
     *  Ian Robertson; http://www.artima.com/weblogs/viewpost.jsp?thread=208860
     *
     * @param type the type
     * @return the underlying class
     */
    public static Class<?> getClass(Type type) {
        if (type instanceof Class) {
            return (Class) type;
        }
        else if (type instanceof ParameterizedType) {
            return getClass(((ParameterizedType) type).getRawType());
        }
        else if (type instanceof GenericArrayType) {
            Type componentType = ((GenericArrayType) type).getGenericComponentType();
            Class<?> componentClass = getClass(componentType);
            if (componentClass != null ) {
                return Array.newInstance(componentClass, 0).getClass();
            }
            else {
                return null;
            }
        }
        else {
            return null;
        }
    }


    /**
     * Get the actual type arguments a child class has used to extend a generic base class.
     *
     *  Ian Robertson; http://www.artima.com/weblogs/viewpost.jsp?thread=208860
     *
     * @param baseClass  the base class
     * @param childClass the child class
     * @param <T>        the base class type
     * @return a list of the raw classes for the actual type arguments.
     */
    public static <T> List<Class<?>> getTypeArguments(Class<T> baseClass, Class<? extends T> childClass) {
        Map<Type, Type> resolvedTypes = new HashMap<Type, Type>();
        Type type = childClass;
        // start walking up the inheritance hierarchy until we hit baseClass
        while (! getClass(type).equals(baseClass)) {
            if (type instanceof Class) {
                // there is no useful information for us in raw types, so just keep going.
                type = ((Class) type).getGenericSuperclass();
            }
            else {
                ParameterizedType parameterizedType = (ParameterizedType) type;
                Class<?> rawType = (Class) parameterizedType.getRawType();

                Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
                TypeVariable<?>[] typeParameters = rawType.getTypeParameters();
                for (int i = 0; i < actualTypeArguments.length; i++) {
                    resolvedTypes.put(typeParameters[i], actualTypeArguments[i]);
                }

                if (!rawType.equals(baseClass)) {
                    type = rawType.getGenericSuperclass();
                }
            }
        }

        // finally, for each actual type argument provided to baseClass, determine (if possible)
        // the raw class for that type argument.
        Type[] actualTypeArguments;
        if (type instanceof Class) {
            actualTypeArguments = ((Class) type).getTypeParameters();
        }
        else {
            actualTypeArguments = ((ParameterizedType) type).getActualTypeArguments();
        }
        List<Class<?>> typeArgumentsAsClasses = new ArrayList<Class<?>>();
        // resolve types by chasing down type variables.
        for (Type baseType: actualTypeArguments) {
            while (resolvedTypes.containsKey(baseType)) {
                baseType = resolvedTypes.get(baseType);
            }
            typeArgumentsAsClasses.add(getClass(baseType));
        }
        return typeArgumentsAsClasses;
    }


}
