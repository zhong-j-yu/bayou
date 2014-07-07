package bayou.reload;

import java.lang.reflect.*;
import java.util.*;

// from any class, visit all classes it openly references, recursively.
class VisitClasses
{
    ClassLoader[] parentLoaders;

    HashMap<String,Class<?>> visitedClasses;
    HashSet<Type>            visitedTypes = new HashSet<>(); // no Class

    ArrayDeque<Type> toExpand = new ArrayDeque<>();

    VisitClasses(ClassLoader parentCL, HashMap<String, Class<?>> visitedClasses)
    {
        this.visitedClasses = visitedClasses;

        ArrayList<ClassLoader> parentList = new ArrayList<>();
        while(parentCL!=null)
        {
            parentList.add(parentCL);
            parentCL=parentCL.getParent();
        }
        this.parentLoaders = parentList.toArray(new ClassLoader[parentList.size()]);
        // not including the 'null' class loader, i.e. the bootstrap class loader
    }

    void add(Class... classes)
    {
        // breadth first traversal
        toExpand(classes);
        while(!toExpand.isEmpty())
        {
            Type type = toExpand.removeFirst();

            if(type instanceof Class)
            {
                Class clazz = (Class)type;
                // clazz is class/interface
                assert !clazz.isPrimitive();
                assert !clazz.isArray();

                toExpand(clazz.getTypeParameters());
                toExpand(clazz.getGenericSuperclass());
                toExpand(clazz.getGenericInterfaces());
                toExpand(getOuterClass(clazz));

                for(Field m : clazz.getDeclaredFields())
                    if(isOpenly(m))
                    {
                        toExpand(m.getGenericType());
                    }
                for(Constructor<?> m : clazz.getDeclaredConstructors())
                    if(isOpenly(m))
                    {
                        toExpand(m.getTypeParameters());
                        toExpand(m.getGenericParameterTypes());
                        toExpand(m.getGenericExceptionTypes());
                    }
                for(Method m : clazz.getDeclaredMethods())
                    if(isOpenly(m))
                    {
                        toExpand(m.getTypeParameters());
                        toExpand(m.getGenericParameterTypes());
                        toExpand(m.getGenericExceptionTypes());
                        toExpand(m.getGenericReturnType());
                    }
                // ctor/method: user may need to manually add unchecked exception classes that can be
                // thrown but not declared, if they need to be shared.

                // TBA: annotations used in this class. probably not useful.
                // user need to manually add annotation classes that need to be shared.
            }
            else if(type instanceof ParameterizedType)
            {
                ParameterizedType t = (ParameterizedType)type;
                toExpand(getOuterType(t));
                toExpand(t.getRawType());
                toExpand(t.getActualTypeArguments());
            }
            else if(type instanceof GenericArrayType)
            {
                GenericArrayType t = (GenericArrayType)type;
                toExpand(t.getGenericComponentType());
            }
            else if(type instanceof TypeVariable)
            {
                TypeVariable t = (TypeVariable)type;
                toExpand(t.getBounds());
            }
            else if(type instanceof WildcardType)
            {
                WildcardType t = (WildcardType)type;
                toExpand(t.getUpperBounds());
                toExpand(t.getLowerBounds());
            }
            else
                throw new AssertionError("unknown Type: "+type.getClass());
        }

        if(false)
        {
            for(String name : new TreeSet<>(visitedClasses.keySet()))
                System.out.println("## "+name);
        }
    }

    void toExpand(Type type)
    {
        if(type==null)
            return;

        if(type instanceof Class)
        {
            Class clazz = (Class)type;
            while(clazz.isArray())
                clazz = clazz.getComponentType();

            // do not expand classes in parent loaders
            ClassLoader cl = clazz.getClassLoader();
            if(cl==null) // bootstrap class: void, primitive, java.*, etc. very common.
                return;
            for(ClassLoader p : parentLoaders)
                if(p==cl)     // probably the extension class loader. unlikely (that ext class appear in public API)
                    return;   // however, javafx.* is apparently in jre/lib/ext at this time.

            String className = clazz.getName();
            if(visitedClasses.containsKey(className))
                return;
            visitedClasses.put(className, clazz);
            toExpand.addLast(clazz);
        }
        else
        {
            if(visitedTypes.contains(type))
                return;
            visitedTypes.add(type);
            toExpand.addLast(type);
        }
    }
    void toExpand(Type[] types)
    {
        for(Type type : types)
            toExpand(type);
    }

    static boolean isOpenly(Member member)
    {
        return 0!=( member.getModifiers() & (Modifier.PUBLIC|Modifier.PROTECTED) );
    }

    static Class getOuterClass(Class clazz)
    {
        if(Modifier.isStatic(clazz.getModifiers())) // static member class. no dependency on the declaring class.
            return null;
        return clazz.getDeclaringClass(); // non-null if clazz is an inner member class
    }
    static Type getOuterType(ParameterizedType t)
    {
        Type outer = t.getOwnerType();
        if(outer instanceof Class) // silly, `t` is static member of `owner`
        {
            // Outer.Inner<X>
            // case 1: Inner is a static member type of Outer. do not expand to Outer.
            // case 2: Inner is an inner member type of Outer; Outer has no type param.
            //         need to expand to Outer. will be handled by getOuterClass(Inner.class)
            return null;
        }
        else // outer is ParameterizedType like O<Z>. expand O and Z
            return outer;
    }

}
