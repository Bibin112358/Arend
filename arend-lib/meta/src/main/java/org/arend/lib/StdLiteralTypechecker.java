package org.arend.lib;

import org.arend.ext.LiteralTypechecker;
import org.arend.ext.concrete.ConcreteFactory;
import org.arend.ext.concrete.expr.ConcreteExpression;
import org.arend.ext.concrete.expr.ConcreteReferenceExpression;
import org.arend.ext.concrete.expr.ConcreteTupleExpression;
import org.arend.ext.core.expr.CoreDataCallExpression;
import org.arend.ext.core.expr.CoreExpression;
import org.arend.ext.core.ops.NormalizationMode;
import org.arend.ext.module.FullName;
import org.arend.ext.reference.ArendRef;
import org.arend.ext.reference.ExpressionResolver;
import org.arend.ext.typechecking.ContextData;
import org.arend.ext.typechecking.ExpressionTypechecker;
import org.arend.ext.typechecking.TypedExpression;
import org.arend.lib.util.Names;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class StdLiteralTypechecker implements LiteralTypechecker {
  private static ArendRef resolveName(FullName fullName, ExpressionResolver resolver) {
    ArendRef ref = resolver.resolveName(fullName.longName.getLastName());
    if (ref != null && ref.checkName(fullName)) {
      return ref;
    }
    ref = resolver.resolveLongName(fullName.longName);
    return ref != null && ref.checkName(fullName) ? ref : null;
  }

  @Override
  public @Nullable ConcreteExpression resolveNumber(@NotNull BigInteger number, @NotNull ExpressionResolver resolver, @NotNull ContextData contextData) {
    ArendRef negative;
    if (number.signum() < 0) {
      negative = resolveName(Names.NEGATIVE, resolver);
      if (negative == null) return null;
      number = number.negate();
    } else {
      negative = null;
    }

    boolean isNatCoef = false;
    FullName fullName;
    if (number.equals(BigInteger.ZERO)) {
      fullName = Names.ZRO;
    } else if (number.equals(BigInteger.ONE)) {
      fullName = Names.IDE;
    } else {
      fullName = Names.NAT_COEF;
      isNatCoef = true;
    }
    ArendRef ref = resolveName(fullName, resolver);
    if (ref == null) return null;

    ConcreteFactory factory = contextData.getFactory();
    ConcreteExpression result = factory.ref(ref);
    if (isNatCoef) {
      result = factory.app(result, true, factory.number(number));
    }
    return negative == null ? result : factory.app(factory.ref(negative), true, result);
  }

  @Override
  public @Nullable TypedExpression typecheckNumber(@NotNull BigInteger number, @Nullable ConcreteExpression resolved, @NotNull ExpressionTypechecker typechecker, @NotNull ContextData contextData) {
    if (resolved != null) {
      CoreExpression expectedType = contextData.getExpectedType() == null ? null : contextData.getExpectedType().normalize(NormalizationMode.WHNF);
      if (expectedType != null && !(expectedType instanceof CoreDataCallExpression dataCall && (dataCall.getDefinition() == typechecker.getPrelude().getNat() || dataCall.getDefinition() == typechecker.getPrelude().getInt() || dataCall.getDefinition() == typechecker.getPrelude().getFin()))) {
        return typechecker.typecheck(resolved, expectedType);
      }
    }
    return typechecker.checkNumber(number, contextData.getExpectedType(), contextData.getMarker());
  }

  // Resolution only has an ExpressionResolver (no typechecker), so it just resolves the names the
  // typechecking side will need and passes them through as a 3-tuple. Actual array construction
  // happens in typecheckString, via ExpressionTypechecker.checkArray -- a direct core-level builder
  // that takes a plain List of already-typechecked elements, so it has no concrete-syntax-tree
  // depth dependency on the literal's length at all (unlike building a `::`/`nil` chain via
  // ConcreteFactory.app(), which overflowed the stack of concrete-tree visitors like
  // SyntacticDesugarVisitor for anything much longer than a few hundred bytes).
  @Override
  public @Nullable ConcreteExpression resolveString(@NotNull String unescapedString, @NotNull ExpressionResolver resolver, @NotNull ContextData contextData) {
    ArendRef stringRef = resolveName(Names.STRING, resolver);
    ArendRef bytesRef = resolveName(Names.STRING_BYTES, resolver);
    ArendRef byteRef = resolveName(Names.BYTE, resolver);
    if (stringRef == null || bytesRef == null || byteRef == null) return null;

    ConcreteFactory factory = contextData.getFactory();
    return factory.tuple(factory.ref(stringRef), factory.ref(bytesRef), factory.ref(byteRef));
  }

  private static @Nullable ArendRef asRef(ConcreteExpression expr) {
    return expr instanceof ConcreteReferenceExpression ref ? ref.getReferent() : null;
  }

  @Override
  public @Nullable TypedExpression typecheckString(@NotNull String unescapedString, @Nullable ConcreteExpression resolved, @NotNull ExpressionTypechecker typechecker, @NotNull ContextData contextData) {
    if (!(resolved instanceof ConcreteTupleExpression tuple) || tuple.getFields().size() != 3) return null;
    ArendRef stringRef = asRef(tuple.getFields().get(0));
    ArendRef bytesRef = asRef(tuple.getFields().get(1));
    ArendRef byteRef = asRef(tuple.getFields().get(2));
    if (stringRef == null || bytesRef == null || byteRef == null) return null;

    ConcreteFactory factory = contextData.getFactory();
    ConcreteExpression marker = contextData.getMarker();
    TypedExpression byteTypeResult = typechecker.typecheck(factory.ref(byteRef), null);
    if (byteTypeResult == null) return null;
    CoreExpression byteType = byteTypeResult.getExpression();

    byte[] bytes = unescapedString.getBytes(StandardCharsets.UTF_8);
    List<TypedExpression> elements = new ArrayList<>(bytes.length);
    for (byte b : bytes) {
      TypedExpression element = typechecker.checkNumber(BigInteger.valueOf(b & 0xFF), byteType, marker);
      if (element == null) return null;
      elements.add(element);
    }

    TypedExpression array = typechecker.checkArray(elements, byteType, null, marker);
    if (array == null) return null;

    ConcreteExpression newExpr = factory.newExpr(factory.classExt(factory.ref(stringRef), factory.implementation(bytesRef, factory.core(array))));
    return typechecker.typecheck(newExpr, contextData.getExpectedType());
  }
}