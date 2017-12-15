package io.projectdraco.dependenciescollector.staticdependencies;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.*;
import com.github.javaparser.ast.visitor.*;
import com.github.javaparser.resolution.*;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.types.*;
import com.github.javaparser.symbolsolver.*;
import com.github.javaparser.symbolsolver.core.resolution.*;
import com.github.javaparser.symbolsolver.javaparser.*;
import com.github.javaparser.symbolsolver.javaparsermodel.*;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.*;
import com.github.javaparser.symbolsolver.javaparsermodel.contexts.*;
import com.github.javaparser.symbolsolver.logic.*;
import com.github.javaparser.symbolsolver.model.resolution.*;
import com.github.javaparser.symbolsolver.reflectionmodel.*;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.function.*;

public class Main {

    public static void main(String[] args) throws Exception {
        for (String s : args) {
            File f = new File(s);
            JavaParserTypeSolver jpts = new JavaParserTypeSolver(f);
            JavaSymbolSolver jss = new JavaSymbolSolver(jpts);

            TypeSolvers typeSolvers = new TypeSolvers();
            typeSolvers.typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver(), jpts);
            typeSolvers.externalTypeSolver = new MemoryTypeSolver() {
                public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
                    SymbolReference<ResolvedReferenceTypeDeclaration> result = super.tryToSolveType(name);
                    if (!result.isSolved()) {
                        String[] arr = name.split("\\.");
                        result = super.tryToSolveType(arr[arr.length-1]);
                    }
                    return result;
                }
            };

            Supplier<Stream<CompilationUnit>> ss = () -> walk(Paths.get(s))
                .filter(p -> p.toString().endsWith(".java"))
                .map(p -> parse(p.toAbsolutePath().toString()));
            // collect external superclasses and interfaces
            VoidVisitor<TypeSolvers> externalDeclarationsVisitor = new ExternalDeclarationVisitor();
            ss.get().forEach(cu -> {
                externalDeclarationsVisitor.visit(cu, typeSolvers);
                if (cu.getImports() != null) {
                    for (ImportDeclaration imp : cu.getImports()) {
                        if (imp.isStatic() && !imp.isAsterisk()) {
                            String name = imp.getNameAsString();
                            name = name.substring(0, name.lastIndexOf('.'));
                            typeSolvers.externalTypeSolver.addDeclaration(
                                    name, new ExternalResolvedReferenceTypeDeclaration(name, null, null, typeSolvers.typeSolver));
                        }
                    }
                }
            });
            typeSolvers.typeSolver.add(typeSolvers.externalTypeSolver);
            // run printer visitor
            VoidVisitor<JavaParserFacade> visitor = new StaticDependencyPrinter();
            ss.get().forEach(cu -> {
                jss.inject(cu);
                try {
                    visitor.visit(cu, JavaParserFacade.get(typeSolvers.typeSolver));
                } catch (Exception e) {
                    throw new RuntimeException(cu.getStorage().get().getPath().toString(), e);
                }
            });
        }
    }

    private static Stream<Path> walk(Path p) {
        try {
            return Files.walk(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static CompilationUnit parse(String path) {
        try {
            return JavaParser.parse(new File(path));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class TypeSolvers {
        public CombinedTypeSolver typeSolver;
        public MemoryTypeSolver externalTypeSolver;
    }

    private static class ExternalResolvedReferenceTypeDeclaration extends ReflectionClassDeclaration {
        private String name;
        private ClassOrInterfaceType coit;
        private Node coid;
        private TypeSolver typeSolver;
        public ExternalResolvedReferenceTypeDeclaration(String name, ClassOrInterfaceType coit, Node coid, TypeSolver typeSolver) {
            super(Object.class, typeSolver);
            this.name = name;
            this.coit = coit;
            this.coid = coid;
            this.typeSolver = typeSolver;
        }
        public ExternalResolvedReferenceTypeDeclaration(Class<?> clazz, TypeSolver typeSolver) {
            super(clazz, typeSolver);
        }
        public String getQualifiedName() {
            return name;
        }
        public List<ResolvedTypeParameterDeclaration> getTypeParameters() {
            if (coit != null && coit.getTypeArguments().isPresent()) {
                return coit.getTypeArguments().get().stream()
                    .map(t -> {
                        TypeParameter tp = new TypeParameter(t.toString());
                        tp.setParentNode(coid);
                        return new JavaParserTypeParameter(tp, typeSolver);
                    })
                    .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private static class ExternalDeclarationVisitor extends VoidVisitorAdapter<TypeSolvers> {

        @Override
        public void visit(ClassOrInterfaceDeclaration coid, TypeSolvers ts) {
            super.visit(coid, ts);
            if (coid.isInterface() || coid.isLocalClassDeclaration() /*|| coid.isInnerClass()*/) {
                return;
            }
            for (ClassOrInterfaceType coit : coid.getExtendedTypes()) {
                addUnsolvedClassesOrInterfaces(coit, coid, ts);
            }
            for (ClassOrInterfaceType coit : coid.getImplementedTypes()) {
                addUnsolvedClassesOrInterfaces(coit, coid, ts);
            }
        }

        @Override
        public void visit(MethodDeclaration md, TypeSolvers ts) {
            super.visit(md, ts);
            if (md.getType() instanceof ClassOrInterfaceType) {
                addUnsolvedClassesOrInterfaces((ClassOrInterfaceType) md.getType(), new ClassOrInterfaceDeclaration(), ts);
            }
            for (com.github.javaparser.ast.body.Parameter p : md.getParameters()) {
                if (p.getType() instanceof ClassOrInterfaceType) {
                    addUnsolvedClassesOrInterfaces((ClassOrInterfaceType) p.getType(), new ClassOrInterfaceDeclaration(), ts);
                }
            }
        }

        @Override
        public void visit(FieldDeclaration fd, TypeSolvers ts) {
            super.visit(fd, ts);
            for (VariableDeclarator vd : fd.getVariables()) {
                if (vd.getType() instanceof ClassOrInterfaceType) {
                    addUnsolvedClassesOrInterfaces((ClassOrInterfaceType) vd.getType(), new ClassOrInterfaceDeclaration(), ts);
                }
            }
        }

        @Override
        public void visit(ObjectCreationExpr oce, TypeSolvers ts) {
            super.visit(oce, ts);
            if (oce.getType() instanceof ClassOrInterfaceType) {
                addUnsolvedClassesOrInterfaces((ClassOrInterfaceType) oce.getType(), new ClassOrInterfaceDeclaration(), ts);
            }
        }

        private void addUnsolvedClassesOrInterfaces(ClassOrInterfaceType coit, ClassOrInterfaceDeclaration coid, TypeSolvers typeSolvers) {
            String name = coit.getNameAsString();
            if (coit.getScope().isPresent()) {
                name = coit.getScope().get().toString() + "." + name;
            }
            SymbolReference<ResolvedReferenceTypeDeclaration> ref = typeSolvers.typeSolver.tryToSolveType(name);
            if (!ref.isSolved()) {
                SymbolReference<ResolvedReferenceTypeDeclaration> solved =
                    typeSolvers.externalTypeSolver.tryToSolveType(name);
                int count = coit.getTypeArguments().isPresent() ? coit.getTypeArguments().get().size() : 0;
                if (!solved.isSolved() || solved.getCorrespondingDeclaration().getTypeParameters().size() < count) {
                    typeSolvers.externalTypeSolver.addDeclaration(
                            name, new ExternalResolvedReferenceTypeDeclaration(name, coit, coid, typeSolvers.typeSolver));
                }
                if (name.contains(".")) {
                    String[] arr = name.split("\\.");
                    typeSolvers.externalTypeSolver.addDeclaration(
                            arr[arr.length-1],
                            new ExternalResolvedReferenceTypeDeclaration(arr[arr.length-1], coit, coid, typeSolvers.typeSolver));
                }
            }
            if (coit.getTypeArguments().isPresent()) {
                for (com.github.javaparser.ast.type.Type t : coit.getTypeArguments().get()) {
                    if (t instanceof ClassOrInterfaceType) {
                        addUnsolvedClassesOrInterfaces((ClassOrInterfaceType) t, coid, typeSolvers);
                    }
                }
            }
        }
    }

    private static class StaticDependencyPrinter extends VoidVisitorAdapter<JavaParserFacade> {

        @Override
        public void visit(MethodCallExpr mc, JavaParserFacade jp) {
            super.visit(mc, jp);
            // Check if the receiver type is on sources
            TypeSolver typeSolver = jp.getTypeSolver();
            Context ctx = JavaParserFactory.getContext(mc, typeSolver);
            Collection<ResolvedReferenceTypeDeclaration> rts = findTypeDeclarations(mc, mc.getScope(), ctx, jp);
            if (rts.isEmpty()) {
                return;
            }
            //
            SymbolReference<ResolvedMethodDeclaration> ref = null;
            try {
                ref = jp.solve(mc);
            } catch (Exception e) {
                // jp = JavaParserFacade.get(new ReflectionTypeSolver());
                // ref = jp.solve(mc);
                // TODO: resolve inner classes referenced by interface name rather than class name
                String msg = e.toString();
                if (e.getCause() != null) {
                    msg = e.getCause().toString();
                }
                System.err.println/*throw new RuntimeException*/("Cannot solve method call " + mc + " " + msg + " " + mc.findCompilationUnit().get().getStorage().get().getPath()/*, e*/);
            }
            if (ref == null || !ref.isSolved()) return;
            String cupath = getCompilationUnitPath(findCompilationUnit(mc, jp));
            if (cupath.length() == 0) return;
            BodyDeclaration bd = mc.findParent(BodyDeclaration.class).get();
            System.out.print(fullQualifiedSignature(bd, jp) + " ");
            ResolvedMethodDeclaration rmd = ref.getCorrespondingDeclaration();
            System.out.print(cupath);
            System.out.println(rmd.getQualifiedSignature());
        }

        @Override
        public void visit(FieldAccessExpr fa, JavaParserFacade jp) {
            super.visit(fa, jp);
            SymbolReference<ResolvedFieldDeclaration> ref = solve(fa, jp);
            if (!ref.isSolved()) return;
            String cupath = getCompilationUnitPath(findCompilationUnit(fa, jp));
            if (cupath.length() == 0) return;
            BodyDeclaration bd = fa.findParent(BodyDeclaration.class).get();
            System.out.print(fullQualifiedSignature(bd, jp) + " ");
            ResolvedFieldDeclaration rfd = ref.getCorrespondingDeclaration();
            System.out.print(cupath);
            try {
                System.out.println(rfd.declaringType().getQualifiedName() + "." + fa.getName().getId());
            } catch (Exception e) {
                System.err.println("Cannot solve field access " + fa);
            }
        }

        @Override
        public void visit(NameExpr ne, JavaParserFacade jp) {
            super.visit(ne, jp);
            SymbolReference<? extends ResolvedValueDeclaration> ref = null;
            try {
                ref = jp.solve(ne);
            } catch (Exception e) {
                // TODO: solve expressions inside inner classes
                System.err.println("Cannot solve name expression " + ne);
                return;
            }
            if (!ref.isSolved() || !ref.getCorrespondingDeclaration().isField()) { return; }
            BodyDeclaration bd = ne.findParent(BodyDeclaration.class).get();
            System.out.print(fullQualifiedSignature(bd, jp) + " ");
            System.out.print(getCompilationUnitPath(ne.findCompilationUnit()));
            ResolvedFieldDeclaration rfd = ref.getCorrespondingDeclaration().asField();
            try {
                System.out.println(rfd.declaringType().getQualifiedName() + "." + ne.getName().getId());
            } catch (Exception e) {
                // TODO: verify why the ne is empty
                System.err.println("Empty name expression " + ne);
            }
        }
    }

    private static SymbolReference<ResolvedFieldDeclaration> solve(FieldAccessExpr fa, JavaParserFacade jp) {
        TypeSolver typeSolver = jp.getTypeSolver();
        FieldAccessContext ctx = ((FieldAccessContext) JavaParserFactory.getContext(fa, typeSolver));
        Optional<Expression> scope = Optional.of(fa.getScope());
        Collection<ResolvedReferenceTypeDeclaration> rt = findTypeDeclarations(fa, scope, ctx, jp);
        for (ResolvedReferenceTypeDeclaration r : rt) {
            ResolvedFieldDeclaration rfd = null;
            try {
                rfd = r.getField(fa.getName().getId());
                if (rfd != null) {
                    return SymbolReference.solved(rfd);
                }
            } catch (Throwable t) {
                System.err.println("Cannot resolve field access " + rfd);
            }
        }
        return SymbolReference.unsolved(ResolvedFieldDeclaration.class);
    }

    private static String fullQualifiedSignature(BodyDeclaration bd, JavaParserFacade jp) {
        if (bd instanceof MethodDeclaration) {
            ResolvedMethodDeclaration callingRmd =
                new JavaParserMethodDeclaration((MethodDeclaration) bd, jp.getTypeSolver());
            try {
                return getCompilationUnitPath(bd.findCompilationUnit()) + callingRmd.getQualifiedSignature();
            } catch (Exception e) {
                return getCompilationUnitPath(bd.findCompilationUnit()) + ((MethodDeclaration) bd).getSignature();
            }
        } else if (bd instanceof FieldDeclaration) {
            ResolvedFieldDeclaration fd =
                new JavaParserFieldDeclaration(((FieldDeclaration) bd).getVariable(0), jp.getTypeSolver());
            return getCompilationUnitPath(bd.findCompilationUnit()) +
                fd.declaringType().getQualifiedName() + "." + fd.getName();
        }
        return "<unsupported> " + bd.getClass();
    }

    private static Collection<ResolvedReferenceTypeDeclaration> findTypeDeclarations(
            Node node, Optional<Expression> scope, Context ctx, JavaParserFacade jp) {
        Collection<ResolvedReferenceTypeDeclaration> rt = new ArrayList<>();
        TypeSolver typeSolver = jp.getTypeSolver();
        SymbolReference<ResolvedTypeDeclaration> ref = null;
        try {
            if (scope.isPresent()) {
                if (scope.get() instanceof NameExpr) {
                    NameExpr scopeAsName = (NameExpr) scope.get();
                    try {
                        ref = ctx.solveType(scopeAsName.getName().getId(), typeSolver);
                    } catch (UnsupportedOperationException e) {
                        if (e.getMessage().contains("InternalTypes not available for")) {
                            return rt;
                        }
                        throw e;
                    }
                }
                if (ref == null || !ref.isSolved()) {
                    ResolvedType typeOfScope = null;
                    try {
                        typeOfScope = jp.getType(scope.get());
                    } catch (com.github.javaparser.symbolsolver.javaparsermodel.UnsolvedSymbolException
                            | com.github.javaparser.resolution.UnsolvedSymbolException e) {
                        return rt;
                    } catch (RuntimeException e) {
                        Throwable cause = e;
                        while (cause != null) {
                            if (cause.getMessage() != null && cause.getMessage().contains("Method") &&
                                    cause.getMessage().contains("cannot be resolved in context")) {
                                System.err.println(e.getMessage());
                                return rt;
                            }
                            if (cause.getMessage() != null &&
                                    cause.getMessage().contains("Error calculating the type of parameter")) {
                                System.err.println(e.getMessage());
                                return rt;
                            }
                            if (cause instanceof
                                    com.github.javaparser.symbolsolver.javaparsermodel.UnsolvedSymbolException ||
                                    cause instanceof com.github.javaparser.resolution.UnsolvedSymbolException ||
                                    cause instanceof UnsupportedOperationException) {
                                return rt;
                            }
                            if (cause == cause.getCause()) {
                                cause = null;
                            } else {
                                cause = cause.getCause();
                            }
                        }
                        throw e;
                    }
                    if (typeOfScope.isWildcard()) {
                        if (typeOfScope.asWildcard().isExtends() || typeOfScope.asWildcard().isSuper()) {
                            rt.add(typeOfScope.asWildcard().getBoundedType().asReferenceType().getTypeDeclaration());
                        } else {
                            rt.add(new ReflectionClassDeclaration(Object.class, typeSolver).asReferenceType());
                        }
                    } else if (typeOfScope.isArray()) {
                        rt.add(new ReflectionClassDeclaration(Object.class, typeSolver).asReferenceType());
                    } else if (typeOfScope.isTypeVariable()) {
                        for (ResolvedTypeParameterDeclaration.Bound bound : typeOfScope.asTypeParameter().getBounds()) {
                            rt.add(bound.getType().asReferenceType().getTypeDeclaration());
                        }
                    } else if (typeOfScope.isConstraint()) {
                        rt.add(typeOfScope.asConstraintType().getBound().asReferenceType().getTypeDeclaration());
                    } else {
                        if (!(typeOfScope instanceof ResolvedType) ||
                                ((ResolvedType) typeOfScope).isReferenceType()) {
                            rt.add(typeOfScope.asReferenceType().getTypeDeclaration());
                        } else {
                            System.err.println(typeOfScope.toString() + " is not a reference type");
                        }
                    }
                } else {
                    if (!(ref.getCorrespondingDeclaration() instanceof ResolvedType) ||
                            ((ResolvedType) ref.getCorrespondingDeclaration()).isReferenceType()) {
                        rt.add(ref.getCorrespondingDeclaration().asReferenceType());
                    } else {
                        System.err.println(ref.toString() + " is not a reference type");
                    }
                }
            } else {
                rt.add(jp.getTypeOfThisIn(node).asReferenceType().getTypeDeclaration());
            }
        } catch (Exception e) {
            throw new RuntimeException(node.toString(), e);
        }
        return rt;
    }

    private static String getCompilationUnitPath(Optional<CompilationUnit> cu) {
        if (!cu.isPresent() || !cu.get().getStorage().isPresent()) return "";
        return cu.get().getStorage().get().getPath().toString().replaceAll("/", "_") + "/[CN]/";
    }

    private static Optional<CompilationUnit> findCompilationUnit(MethodCallExpr mc, JavaParserFacade jp) {
        Optional<Expression> scope = mc.getScope();
        Context ctx = JavaParserFactory.getContext(mc, jp.getTypeSolver());
        return findCompilationUnit(mc, scope, ctx, jp);
    }

    private static Optional<CompilationUnit> findCompilationUnit(FieldAccessExpr fa, JavaParserFacade jp) {
        Optional<Expression> scope = Optional.of(fa.getScope());
        Context ctx = JavaParserFactory.getContext(fa, jp.getTypeSolver());
        return findCompilationUnit(fa, scope, ctx, jp);
    }

    private static Optional<CompilationUnit> findCompilationUnit(Node node, Optional<Expression> scope, Context ctx, JavaParserFacade jp) {
        Collection<ResolvedReferenceTypeDeclaration> rt = findTypeDeclarations(node, scope, ctx, jp);
        for (ResolvedReferenceTypeDeclaration r : rt) {
            try {
                return findCompilationUnit(r);
            } catch (Throwable t) {
            }
        }
        return Optional.empty();
    }

    private static Optional<CompilationUnit> findCompilationUnit(ResolvedReferenceTypeDeclaration rrtd) {
        if (rrtd instanceof JavaParserInterfaceDeclaration) {
            return ((JavaParserInterfaceDeclaration) rrtd).getWrappedNode().findCompilationUnit();
        } else if (rrtd instanceof JavaParserClassDeclaration) {
            return ((JavaParserClassDeclaration) rrtd).getWrappedNode().findCompilationUnit();
        } else if (rrtd instanceof JavaParserTypeParameter) {
            return ((JavaParserTypeParameter) rrtd).getWrappedNode().findCompilationUnit();
        } else if (rrtd instanceof JavaParserEnumDeclaration) {
            return ((JavaParserEnumDeclaration) rrtd).getWrappedNode().findCompilationUnit();
        // TODO: get wrapped node from annotation
        // } else if (rrtd instanceof JavaParserAnnotationDeclaration) {
        //     return ((JavaParserAnnotationDeclaration) rrtd).getWrappedNode().findCompilationUnit();
        } else {
            throw new IllegalArgumentException(rrtd.toString());
        }
    }
}
