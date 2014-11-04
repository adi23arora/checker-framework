package org.checkerframework.framework.type;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;
import org.checkerframework.framework.type.AnnotatedTypeMirror.*;
import org.checkerframework.framework.type.visitor.AnnotatedTypeScanner;
import org.checkerframework.framework.type.visitor.SimpleAnnotatedTypeVisitor;
import org.checkerframework.framework.util.AnnotatedTypes;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.ErrorReporter;
import org.checkerframework.javacutil.TreeUtils;

import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.*;

public class SupertypeFinder extends SimpleAnnotatedTypeVisitor<List<? extends AnnotatedTypeMirror>, Void> {
    private final Types types;
    private final AnnotatedTypeFactory atypeFactory;
    private final TypeParamReplacer typeParamReplacer;

    SupertypeFinder(AnnotatedTypeFactory atypeFactory) {
        this.atypeFactory = atypeFactory;
        this.types = atypeFactory.types;
        this.typeParamReplacer = new TypeParamReplacer(types);
    }

    public AnnotatedTypeFactory getTypeFactory() {
        return atypeFactory;
    }

    @Override
    public List<AnnotatedTypeMirror> defaultAction(AnnotatedTypeMirror t, Void p) {
        return new ArrayList<AnnotatedTypeMirror>();
    }


    /**
     * Primitive Rules:
     *
     * double >1 float
     * float >1 long
     * long >1 int
     * int >1 char
     * int >1 short
     * short >1 byte
     *
     * For easiness:
     * boxed(primitiveType) >: primitiveType
     */
    @Override
    public List<AnnotatedTypeMirror> visitPrimitive(AnnotatedPrimitiveType type, Void p) {
        List<AnnotatedTypeMirror> superTypes =
                new ArrayList<AnnotatedTypeMirror>();
        Set<AnnotationMirror> annotations = type.getAnnotations();

        // Find Boxed type
        TypeElement boxed = types.boxedClass(type.getUnderlyingType());
        AnnotatedDeclaredType boxedType = atypeFactory.getAnnotatedType(boxed);
        boxedType.replaceAnnotations(annotations);
        superTypes.add(boxedType);

        TypeKind superPrimitiveType = null;

        if (type.getKind() == TypeKind.BOOLEAN) {
            // Nothing
        } else if (type.getKind() == TypeKind.BYTE) {
            superPrimitiveType = TypeKind.SHORT;
        } else if (type.getKind() == TypeKind.CHAR) {
            superPrimitiveType = TypeKind.INT;
        } else if (type.getKind() == TypeKind.DOUBLE) {
            // Nothing
        } else if (type.getKind() == TypeKind.FLOAT) {
            superPrimitiveType = TypeKind.DOUBLE;
        } else if (type.getKind() == TypeKind.INT) {
            superPrimitiveType = TypeKind.LONG;
        } else if (type.getKind() == TypeKind.LONG) {
            superPrimitiveType = TypeKind.FLOAT;
        } else if (type.getKind() == TypeKind.SHORT) {
            superPrimitiveType = TypeKind.INT;
        } else
            assert false: "Forgot the primitive " + type;

        if (superPrimitiveType != null) {
            AnnotatedPrimitiveType superPrimitive = (AnnotatedPrimitiveType)
                    atypeFactory.toAnnotatedType(types.getPrimitiveType(superPrimitiveType), false);
            superPrimitive.addAnnotations(annotations);
            superTypes.add(superPrimitive);
        }

        return superTypes;
    }

    @Override
    public List<AnnotatedDeclaredType> visitDeclared(AnnotatedDeclaredType type, Void p) {
        List<AnnotatedDeclaredType> supertypes = new ArrayList<AnnotatedDeclaredType>();
        // Set<AnnotationMirror> annotations = type.getAnnotations();

        TypeElement typeElement = (TypeElement) type.getUnderlyingType().asElement();

        // Mapping of type variable to actual types
        Map<TypeParameterElement, AnnotatedTypeMirror> mapping = new HashMap<>();

        if(type.getTypeArguments().size() != typeElement.getTypeParameters().size()) {
            if(!type.wasRaw()) {
                ErrorReporter.errorAbort(
                    "AnnotatedDeclaredType's element has a different number of type parameters than type.\n"
                  + "type=" + type + "\n"
                  + "element=" + typeElement);
            }
        }

        for (int i = 0; i < type.getTypeArguments().size(); ++i) {
            mapping.put(typeElement.getTypeParameters().get(i), type.getTypeArguments().get(i));
        }

        ClassTree classTree = atypeFactory.trees.getTree(typeElement);
        // Testing against enum and annotation. Ideally we can simply use element!
        if (classTree != null) {
            supertypes.addAll(supertypesFromTree(type, classTree));
        } else {
            supertypes.addAll(supertypesFromElement(type, typeElement));
            // final Element elem = type.getElement() == null ? typeElement : type.getElement();
        }

        for (AnnotatedDeclaredType dt : supertypes) {
            typeParamReplacer.visit(dt, mapping);
        }

        return supertypes;
    }

    private List<AnnotatedDeclaredType> supertypesFromElement(AnnotatedDeclaredType type, TypeElement typeElement) {
        List<AnnotatedDeclaredType> supertypes = new ArrayList<AnnotatedDeclaredType>();
        // Find the super types: Start with enums and superclass
        if (typeElement.getKind() == ElementKind.ENUM) {
            DeclaredType dt = (DeclaredType) typeElement.getSuperclass();
            AnnotatedDeclaredType adt = (AnnotatedDeclaredType) atypeFactory.toAnnotatedType(dt, false);

            List<AnnotatedTypeMirror> tas = adt.getTypeArguments();
            List<AnnotatedTypeMirror> newtas = new ArrayList<AnnotatedTypeMirror>();
            for (AnnotatedTypeMirror t : tas) {
                // If the type argument of super is the same as the input type
                if (atypeFactory.types.isSameType(t.getUnderlyingType(), type.getUnderlyingType())) {
                    t.addAnnotations(type.getAnnotations());
                    newtas.add(t);
                }
            }
            adt.setTypeArguments(newtas);

            supertypes.add(adt);

        } else if (typeElement.getSuperclass().getKind() != TypeKind.NONE) {
            DeclaredType superClass = (DeclaredType) typeElement.getSuperclass();
            AnnotatedDeclaredType dt =
                    (AnnotatedDeclaredType) atypeFactory.toAnnotatedType(superClass, false);
            supertypes.add(dt);

        } else if (!ElementUtils.isObject(typeElement)) {
            supertypes.add(AnnotatedTypeMirror.createTypeOfObject(atypeFactory));
        }

        for (TypeMirror st : typeElement.getInterfaces()) {
            if(type.wasRaw()) {
                st = types.erasure(st);
            }
            AnnotatedDeclaredType ast =
                    (AnnotatedDeclaredType) atypeFactory.toAnnotatedType(st, false);
            supertypes.add(ast);
            if(type.wasRaw()) {
                if(st instanceof DeclaredType) {
                    final List<? extends TypeMirror> typeArgs = ((DeclaredType) st).getTypeArguments();
                    final List<AnnotatedTypeMirror> annotatedTypeArgs = ast.getTypeArguments();
                    for (int i = 0; i < typeArgs.size(); i++) {
                        atypeFactory.annotateImplicit(types.asElement(typeArgs.get(i)), annotatedTypeArgs.get(i));
                    }
                }
            }
        }
        ElementAnnotationApplier.annotateSupers(supertypes, typeElement);

        if (type.wasRaw()) {
            for (AnnotatedDeclaredType adt : supertypes) {
                adt.setWasRaw();
            }
        }
        return supertypes;
    }

    private List<AnnotatedDeclaredType> supertypesFromTree(AnnotatedDeclaredType type, ClassTree classTree) {
        List<AnnotatedDeclaredType> supertypes = new ArrayList<AnnotatedDeclaredType>();
        if (classTree.getExtendsClause() != null) {
            AnnotatedDeclaredType adt = (AnnotatedDeclaredType)
                    atypeFactory.fromTypeTree(classTree.getExtendsClause());
            supertypes.add(adt);
        } else if (!ElementUtils.isObject(TreeUtils.elementFromDeclaration(classTree))) {
            supertypes.add(AnnotatedTypeMirror.createTypeOfObject(atypeFactory));
        }

        for (Tree implemented : classTree.getImplementsClause()) {
            AnnotatedDeclaredType adt = (AnnotatedDeclaredType)
                    atypeFactory.getAnnotatedTypeFromTypeTree(implemented);
            supertypes.add(adt);
        }

        TypeElement elem = TreeUtils.elementFromDeclaration(classTree);
        if (elem.getKind() == ElementKind.ENUM) {
            DeclaredType dt = (DeclaredType) elem.getSuperclass();
            AnnotatedDeclaredType adt = (AnnotatedDeclaredType) atypeFactory.toAnnotatedType(dt, false);
            List<AnnotatedTypeMirror> tas = adt.getTypeArguments();
            List<AnnotatedTypeMirror> newtas = new ArrayList<AnnotatedTypeMirror>();
            for (AnnotatedTypeMirror t : tas) {
                // If the type argument of super is the same as the input type
                if (atypeFactory.types.isSameType(t.getUnderlyingType(), type.getUnderlyingType())) {
                    t.addAnnotations(type.getAnnotations());
                    newtas.add(t);
                }
            }
            adt.setTypeArguments(newtas);
            supertypes.add(adt);
        }
        if (type.wasRaw()) {
            for (AnnotatedDeclaredType adt : supertypes) {
                adt.setWasRaw();
            }
        }
        return supertypes;
    }

    /**
     * For type = A[ ] ==>
     *  Object >: A[ ]
     *  Clonable >: A[ ]
     *  java.io.Serializable >: A[ ]
     *
     * if A is reference type, then also
     *  B[ ] >: A[ ] for any B[ ] >: A[ ]
     */
    @Override
    public List<AnnotatedTypeMirror> visitArray(AnnotatedArrayType type, Void p) {
        List<AnnotatedTypeMirror> superTypes = new ArrayList<AnnotatedTypeMirror>();
        Set<AnnotationMirror> annotations = type.getAnnotations();
        Elements elements = atypeFactory.elements;
        final AnnotatedTypeMirror objectType =
                atypeFactory.getAnnotatedType(elements.getTypeElement("java.lang.Object"));
        objectType.addAnnotations(annotations);
        superTypes.add(objectType);

        final AnnotatedTypeMirror cloneableType =
                atypeFactory.getAnnotatedType(elements.getTypeElement("java.lang.Cloneable"));
        cloneableType.addAnnotations(annotations);
        superTypes.add(cloneableType);

        final AnnotatedTypeMirror serializableType =
                atypeFactory.getAnnotatedType(elements.getTypeElement("java.io.Serializable"));
        serializableType.addAnnotations(annotations);
        superTypes.add(serializableType);

        if (type.getComponentType() instanceof AnnotatedReferenceType) {
            for (AnnotatedTypeMirror sup : type.getComponentType().directSuperTypes()) {
                ArrayType arrType = atypeFactory.types.getArrayType(sup.getUnderlyingType());
                AnnotatedArrayType aarrType = (AnnotatedArrayType)
                        atypeFactory.toAnnotatedType(arrType, false);
                aarrType.setComponentType(sup);
                aarrType.addAnnotations(annotations);
                superTypes.add(aarrType);
            }
        }

        return superTypes;
    }

    @Override
    public List<AnnotatedTypeMirror> visitTypeVariable(AnnotatedTypeVariable type, Void p) {
        List<AnnotatedTypeMirror> superTypes = new ArrayList<>();
        superTypes.add(AnnotatedTypes.deepCopy(type.getUpperBound()));
        return superTypes;
    }

    @Override
    public List<AnnotatedTypeMirror> visitWildcard(AnnotatedWildcardType type, Void p) {
        List<AnnotatedTypeMirror> superTypes = new ArrayList<>();
        superTypes.add(AnnotatedTypes.deepCopy(type.getExtendsBound()));
        return superTypes;
    }


    /**
     * Note: The explanation below is my interpretation why we have this code.  I am not sure if this
     * was the author's original intent but I can see no other reasoning, exercise caution:
     *
     * Classes may have type parameters that are used in extends or implements clauses.
     * E.g.
     * class MyList<T> extends List<T>
     *
     * Direct supertypes will contain a type List<T> but the type T may become out of sync with
     * the annotations on type MyList<T>.  To keep them in-sync, we substitute out the copy of T
     * with the same reference to T that is on MyList<T>
     */
    class TypeParamReplacer extends AnnotatedTypeScanner<Void, Map<TypeParameterElement, AnnotatedTypeMirror>> {
        private final Types types;

        public TypeParamReplacer(Types types) {
            this.types = types;
        }

        @Override
        public Void visitDeclared(AnnotatedDeclaredType type, Map<TypeParameterElement, AnnotatedTypeMirror> mapping) {
            List<AnnotatedTypeMirror> args = new ArrayList<AnnotatedTypeMirror>();
            for (AnnotatedTypeMirror arg : type.getTypeArguments()) {
                Element elem = types.asElement(arg.getUnderlyingType());
                if ((elem != null) &&
                        (elem.getKind() == ElementKind.TYPE_PARAMETER) &&
                        (mapping.containsKey(elem))) {
                    AnnotatedTypeMirror other = mapping.get(elem);
                    other.replaceAnnotations(arg.annotations);
                    args.add(other);
                } else {
                    args.add(arg);
                    visit(arg, mapping);
                }
            }
            type.setTypeArguments(args);

            return null;
        }

        @Override
        public Void visitArray(AnnotatedArrayType type, Map<TypeParameterElement, AnnotatedTypeMirror> mapping) {
            AnnotatedTypeMirror comptype = type.getComponentType();
            Element elem = types.asElement(comptype.getUnderlyingType());
            AnnotatedTypeMirror other;
            if ((elem != null) &&
                    (elem.getKind() == ElementKind.TYPE_PARAMETER) &&
                    (mapping.containsKey(elem))) {
                other = mapping.get(elem);
                other.replaceAnnotations(comptype.annotations);
                type.setComponentType(other);
            } else {
                visit(type.getComponentType(), mapping);
            }

            return null;
        }
    }



    // Version of method below for declared types
    /** @see Types#directSupertypes(TypeMirror) */
    public static List<AnnotatedDeclaredType> directSuperTypes(AnnotatedDeclaredType type) {
        SupertypeFinder superTypeFinder = new SupertypeFinder(type.atypeFactory);
        List<AnnotatedDeclaredType> supertypes = superTypeFinder.visitDeclared(type, null);
        type.atypeFactory.postDirectSuperTypes(type, supertypes);
        return supertypes;
    }

    // Version of method above for all types
    /** @see Types#directSupertypes(TypeMirror) */
    public static final List<? extends AnnotatedTypeMirror> directSuperTypes(AnnotatedTypeMirror type) {
        SupertypeFinder superTypeFinder = new SupertypeFinder(type.atypeFactory);
        List<? extends AnnotatedTypeMirror> supertypes = superTypeFinder.visit(type, null);
        type.atypeFactory.postDirectSuperTypes(type, supertypes);
        return supertypes;
    }


}