package io.projectdraco.dependenciescollector.staticdependencies;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.*;
import com.github.javaparser.ast.visitor.*;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.types.*;
import com.github.javaparser.symbolsolver.core.resolution.*;
import com.github.javaparser.symbolsolver.javaparser.*;
import com.github.javaparser.symbolsolver.javaparsermodel.*;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.*;
import com.github.javaparser.symbolsolver.javaparsermodel.contexts.*;
import com.github.javaparser.symbolsolver.model.resolution.*;
import com.github.javaparser.symbolsolver.reflectionmodel.*;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

public class Main {

    public static void main(String[] args) throws Exception {
        for (String s : args) {
            File f = new File(s);
            JavaParserTypeSolver jpts = new JavaParserTypeSolver(f);

            Method m = jpts.getClass().getDeclaredMethod("parseDirectory", File.class);
            m.setAccessible(true);
            Collection<CompilationUnit> cus = (Collection<CompilationUnit>) m.invoke(jpts, f);

            TypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver(), jpts);

            VoidVisitor<JavaParserFacade> methodCallVisitor = new MethodCallPrinter();
            VoidVisitor<JavaParserFacade> fieldAccessVisitor = new FieldAccessPrinter();
            for (CompilationUnit cu : cus) {
                methodCallVisitor.visit(cu, JavaParserFacade.get(typeSolver));
                fieldAccessVisitor.visit(cu, JavaParserFacade.get(typeSolver));
            }
        }
    }

    private static class MethodCallPrinter extends VoidVisitorAdapter<JavaParserFacade> {

        @Override
        public void visit(MethodCallExpr mc, JavaParserFacade jp) {
            super.visit(mc, jp);
            SymbolReference<ResolvedMethodDeclaration> ref = null;
            ref = jp.solve(mc);
            if (!ref.isSolved()) return;
            String cupath = getCompilationUnitPath(findCompilationUnit(mc, jp));
            if (cupath.length() == 0) return;
            MethodDeclaration md = mc.findParent(MethodDeclaration.class).get();
            System.out.print(fullQualifiedSignature(md, jp) + " ");
            ResolvedMethodDeclaration rmd = ref.getCorrespondingDeclaration();
            System.out.print(cupath);
            System.out.println(rmd.getQualifiedSignature());
        }
    }

    private static class FieldAccessPrinter extends VoidVisitorAdapter<JavaParserFacade> {

        @Override
        public void visit(FieldAccessExpr fa, JavaParserFacade jp) {
            super.visit(fa, jp);
            SymbolReference<ResolvedFieldDeclaration> ref = solve(fa, jp);
            if (!ref.isSolved()) return;
            String cupath = getCompilationUnitPath(findCompilationUnit(fa, jp));
            if (cupath.length() == 0) return;
            MethodDeclaration md = fa.findParent(MethodDeclaration.class).get();
            System.out.print(fullQualifiedSignature(md, jp) + " ");
            ResolvedFieldDeclaration rfd = ref.getCorrespondingDeclaration();
            System.out.print(cupath);
            System.out.println(rfd.declaringType().getQualifiedName() + "." + fa.getName().getId());
        }
    }

    private static SymbolReference<ResolvedFieldDeclaration> solve(FieldAccessExpr fa, JavaParserFacade jp) {
        TypeSolver typeSolver = jp.getTypeSolver();
        FieldAccessContext ctx = ((FieldAccessContext) JavaParserFactory.getContext(fa, typeSolver));
        Expression scope = fa.getScope();
        Collection<ResolvedReferenceTypeDeclaration> rt = findTypeDeclarations(scope, ctx, jp);
        for (ResolvedReferenceTypeDeclaration r : rt) {
            try {
                return SymbolReference.solved(r.getField(fa.getName().getId()));
            } catch (Throwable t) {
            }
        }
        return SymbolReference.unsolved(ResolvedFieldDeclaration.class);
    }

    private static String fullQualifiedSignature(MethodDeclaration md, JavaParserFacade jp) {
        ResolvedMethodDeclaration callingRmd = new JavaParserMethodDeclaration(md, jp.getTypeSolver());
        return getCompilationUnitPath(md.findCompilationUnit()) + callingRmd.getQualifiedSignature();
    }

    private static Collection<ResolvedReferenceTypeDeclaration> findTypeDeclarations(
            Expression scope, Context ctx, JavaParserFacade jp) {
        Collection<ResolvedReferenceTypeDeclaration> rt = new ArrayList<>();
        TypeSolver typeSolver = jp.getTypeSolver();
        SymbolReference<ResolvedTypeDeclaration> ref = null;
        if (scope instanceof NameExpr) {
            NameExpr scopeAsName = (NameExpr) scope;
            ref = ctx.solveType(scopeAsName.getName().getId(), typeSolver);
        }
        if (ref == null || !ref.isSolved()) {
            ResolvedType typeOfScope = jp.getType(scope);
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
                rt.add(typeOfScope.asReferenceType().getTypeDeclaration());
            }
        } else {
            rt.add(ref.getCorrespondingDeclaration().asReferenceType());
        }
        return rt;
    }

    private static String getCompilationUnitPath(Optional<CompilationUnit> cu) {
        if (!cu.isPresent() || !cu.get().getStorage().isPresent()) return "";
        return cu.get().getStorage().get().getPath() + "/";
    }

    private static Optional<CompilationUnit> findCompilationUnit(MethodCallExpr mc, JavaParserFacade jp) {
        Optional<Expression> scope = mc.getScope();
        if (!scope.isPresent()) return Optional.empty();
        Context ctx = JavaParserFactory.getContext(mc, jp.getTypeSolver());
        return findCompilationUnit(scope.get(), ctx, jp);
    }

    private static Optional<CompilationUnit> findCompilationUnit(FieldAccessExpr fa, JavaParserFacade jp) {
        Expression scope =fa.getScope();
        Context ctx = JavaParserFactory.getContext(fa, jp.getTypeSolver());
        return findCompilationUnit(scope, ctx, jp);
    }

    private static Optional<CompilationUnit> findCompilationUnit(Expression scope, Context ctx, JavaParserFacade jp) {
        Collection<ResolvedReferenceTypeDeclaration> rt = findTypeDeclarations(scope, ctx, jp);
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
