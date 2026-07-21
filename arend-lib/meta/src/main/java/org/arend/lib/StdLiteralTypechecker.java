package org.arend.lib;

import org.arend.ext.LiteralTypechecker;
import org.arend.ext.concrete.ConcreteFactory;
import org.arend.ext.concrete.expr.ConcreteExpression;
import org.arend.ext.concrete.expr.ConcreteReferenceExpression;
import org.arend.ext.core.definition.CoreClassDefinition;
import org.arend.ext.core.definition.CoreClassField;
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

  // Resolution only has an ExpressionResolver (no typechecker), so it resolves the one arend-lib name
  // the literal denotes -- the `String` type -- and returns a genuine reference to it (the head of the
  // `\new String { ... }` elaboration, mirroring how resolveNumber returns `natCoef n`). `bytes` is
  // String's own field, so typecheckString derives it from String's definition rather than resolving
  // and carrying it separately. The byte array is built there too, via ExpressionTypechecker.checkByteArray,
  // a compact core-level builder with no concrete-syntax-tree depth dependency on the literal's length
  // (unlike a `::`/`nil` chain, which is quadratic to elaborate and overflows the concrete-tree visitors'
  // stack for large literals).
  @Override
  public @Nullable ConcreteExpression resolveString(@NotNull String unescapedString, @NotNull ExpressionResolver resolver, @NotNull ContextData contextData) {
    ArendRef stringRef = resolveName(Names.STRING, resolver);
    if (stringRef == null) return null;
    return contextData.getFactory().ref(stringRef);
  }

  @Override
  public @Nullable TypedExpression typecheckString(@NotNull String unescapedString, @Nullable ConcreteExpression resolved, @NotNull ExpressionTypechecker typechecker, @NotNull ContextData contextData) {
    if (!(resolved instanceof ConcreteReferenceExpression stringRefExpr)) return null;
    ArendRef stringRef = stringRefExpr.getReferent();
    if (!(typechecker.getCoreDefinition(stringRef) instanceof CoreClassDefinition stringClass)) return null;

    // `bytes` is String's own field, derived from the resolved type here (typecheckString has no
    // resolver) -- the same lookup StringExpressionPrettifier uses to read a String value's bytes.
    CoreClassField bytesField = stringClass.findField("bytes");
    if (bytesField == null) return null;

    TypedExpression array = typechecker.checkByteArray(unescapedString.getBytes(StandardCharsets.UTF_8));

    ConcreteFactory factory = contextData.getFactory();
    ConcreteExpression newExpr = factory.newExpr(factory.classExt(factory.ref(stringRef), factory.implementation(bytesField.getRef(), factory.core(array))));
    return typechecker.typecheck(newExpr, contextData.getExpectedType());
  }
}