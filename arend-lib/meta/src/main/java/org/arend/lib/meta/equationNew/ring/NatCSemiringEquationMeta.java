package org.arend.lib.meta.equationNew.ring;

import org.arend.ext.concrete.ConcreteAppBuilder;
import org.arend.ext.concrete.ConcreteFactory;
import org.arend.ext.concrete.expr.ConcreteArgument;
import org.arend.ext.concrete.expr.ConcreteExpression;
import org.arend.ext.core.context.CoreBinding;
import org.arend.ext.core.definition.CoreFunctionDefinition;
import org.arend.ext.core.expr.CoreClassCallExpression;
import org.arend.ext.core.expr.CoreExpression;
import org.arend.ext.core.expr.CoreFunCallExpression;
import org.arend.ext.core.ops.NormalizationMode;
import org.arend.ext.instance.SubclassSearchParameters;
import org.arend.ext.reference.ArendRef;
import org.arend.ext.typechecking.ContextData;
import org.arend.ext.typechecking.ExpressionTypechecker;
import org.arend.ext.typechecking.TypedExpression;
import org.arend.ext.typechecking.meta.Dependency;
import org.arend.ext.util.Pair;
import org.arend.lib.context.ContextHelper;
import org.arend.lib.meta.equationNew.BaseEquationMeta;
import org.arend.lib.meta.equationNew.term.EquationTerm;
import org.arend.lib.meta.equationNew.term.TermOperation;
import org.arend.lib.meta.linear.Equation;
import org.arend.lib.meta.solver.NatOpsPreprocessor;
import org.arend.lib.ring.Monomial;
import org.arend.lib.util.Names;
import org.arend.lib.util.Utils;
import org.arend.lib.util.Values;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Wrapper meta that augments {@code equation.cSemiring} with Nat-specific
 * div/mod/-' reasoning. On a Nat carrier the wrapper mints synthetic equality
 * hints via {@link NatOpsPreprocessor}, pre-filters them against the goal's
 * polynomial normal form, and then dispatches the unmodified
 * {@code equation.cSemiring} meta with the filtered hints prepended to the
 * user-supplied hint list. On any non-Nat carrier the wrapper is a transparent
 * forward.
 *
 * The class extends {@link CSemiringEquationMeta} only to reuse the inherited
 * reflection pipeline ({@code getOperations}, {@code normalize},
 * {@code parseHint}) needed for the pre-filter computation. The cSemiring
 * meta's {@code invokeMeta} is never called via {@code super}; instead a fresh
 * concrete {@code equation.cSemiring {hints} arg} expression is built and
 * dispatched through the normal meta pipeline via {@link #cSemiringRef}.
 */
public class NatCSemiringEquationMeta extends CSemiringEquationMeta {
  // Live MetaRef for `equation.cSemiring`. Resolved via @Dependency so we pick up
  // the typechecked MetaReferable — not the extension-load-time one (see
  // ReplaceTCRefVisitor, which creates fresh MetaReferables per typechecking
  // context; only the typechecked instance gets setDefinition called on it).
  @Dependency(name = "equation.cSemiring") ArendRef cSemiringRef;

  // Equality-yielding Nat lemmas (the cSemiring path can't use inequality hints).
  @Dependency(name = "-'")          CoreFunctionDefinition truncMinusDef;
  @Dependency(name = "-'")          ArendRef truncMinusRef;
  @Dependency(name = "-'=0")        ArendRef truncMinusEqZero;
  @Dependency(name = "<=_exists")   ArendRef leqExists;
  @Dependency(name = "div_mod")     ArendRef modZeroFromLDiv;
  @Dependency(name = "ldiv*div=id") ArendRef ldivDivEq;

  @Override
  public @Nullable TypedExpression invokeMeta(@NotNull ExpressionTypechecker typechecker, @NotNull ContextData contextData) {
    ConcreteExpression marker = contextData.getMarker();
    ConcreteFactory factory = contextData.getFactory();

    List<? extends ConcreteArgument> arguments = contextData.getArguments();
    ConcreteExpression userHints = arguments.isEmpty() || arguments.getFirst().isExplicit() ? null : arguments.getFirst().getExpression();
    ConcreteExpression argument = arguments.isEmpty() || !arguments.getLast().isExplicit() ? null : arguments.getLast().getExpression();
    CoreExpression expectedType = contextData.getExpectedType();

    CoreFunCallExpression equality;
    if (expectedType == null) {
      if (argument == null) {
        return forwardToCSemiring(typechecker, factory, expectedType, userHints, null);
      }
      TypedExpression argTyped = typechecker.typecheck(argument, null);
      if (argTyped == null) return null;
      equality = Utils.toEquality(argTyped.getType().normalize(NormalizationMode.WHNF), null, null);
      argument = factory.core(argTyped);
    } else {
      equality = Utils.toEquality(expectedType.normalize(NormalizationMode.WHNF), null, null);
    }

    if (equality == null) {
      return forwardToCSemiring(typechecker, factory, expectedType, userHints, argument);
    }

    Pair<TypedExpression, CoreClassCallExpression> instance = Utils.findInstanceWithClassCall(
        new SubclassSearchParameters(getClassDef()), carrier,
        equality.getDefCallArguments().getFirst().normalize(NormalizationMode.WHNF),
        typechecker, marker, getClassDef());
    if (instance == null || !isNatInstance(instance.proj1.getExpression())) {
      return forwardToCSemiring(typechecker, factory, expectedType, userHints, argument);
    }

    // Run the inherited cSemiring reflection pipeline so we can polynomial-divide
    // candidate synth hints against the goal NF and drop the ones that don't
    // apply — cSemiring treats every hint it receives as must-apply.
    Values<CoreExpression> values = new Values<>(typechecker, marker);
    List<TermOperation> operations = getOperations(instance.proj1, instance.proj2, typechecker, factory, marker);
    EquationTerm left = EquationTerm.match(equality.getDefCallArguments().get(1), operations, values);
    EquationTerm right = EquationTerm.match(equality.getDefCallArguments().get(2), operations, values);
    List<Monomial> leftNF = normalize(left);
    List<Monomial> rightNF = normalize(right);

    List<CoreBinding> bindings = new ContextHelper(null).getContextBindings(typechecker);

    List<CoreExpression> scan = new ArrayList<>();
    scan.add(equality.getDefCallArguments().get(1));
    scan.add(equality.getDefCallArguments().get(2));
    for (CoreBinding b : bindings) {
      CoreFunCallExpression bEq = Utils.toEquality(b.getTypeExpr(), null, null);
      if (bEq != null) {
        scan.add(bEq.getDefCallArguments().get(1));
        scan.add(bEq.getDefCallArguments().get(2));
      }
    }

    NatOpsPreprocessor.Refs refs = new NatOpsPreprocessor.Refs(
        truncMinusDef, truncMinusRef,
        null, null, truncMinusEqZero,
        null, null, leqExists,
        modZeroFromLDiv,
        ldivDivEq,
        null);
    NatOpsPreprocessor preprocessor = new NatOpsPreprocessor(typechecker, marker, refs);

    CoreExpression hintType = equality.getDefCallArguments().getFirst();

    List<ConcreteExpression> appliedSynthHints = new ArrayList<>();
    for (NatOpsPreprocessor.SyntheticHypothesis sh : preprocessor.collect(scan, bindings)) {
      if (sh.op != Equation.Operation.EQUALS) continue;
      BaseEquationMeta.Hint<List<Monomial>> hint = parseHint(sh.proof, hintType, operations, values, typechecker);
      if (hint == null) continue;
      if (canApply(hint.leftNF, leftNF) || canApply(hint.leftNF, rightNF)) {
        appliedSynthHints.add(sh.proof);
      }
    }

    List<ConcreteExpression> combined = new ArrayList<>(appliedSynthHints);
    if (userHints != null) combined.addAll(Utils.getArgumentList(userHints));

    ConcreteExpression hintsArg = combined.isEmpty()
        ? null
        : combined.size() == 1 ? combined.getFirst() : factory.tuple(combined);

    return forwardToCSemiring(typechecker, factory, expectedType, hintsArg, argument);
  }

  private TypedExpression forwardToCSemiring(ExpressionTypechecker typechecker, ConcreteFactory factory,
                                             CoreExpression expectedType,
                                             @Nullable ConcreteExpression hints,
                                             @Nullable ConcreteExpression argument) {
    ConcreteAppBuilder builder = factory.appBuilder(factory.ref(cSemiringRef));
    if (hints != null) builder.app(hints, false);
    if (argument != null) builder.app(argument, true);
    return typechecker.typecheck(builder.build(), expectedType);
  }

  private static boolean isNatInstance(CoreExpression instance) {
    return instance.normalize(NormalizationMode.WHNF) instanceof CoreFunCallExpression funCall
        && funCall.getDefinition().getRef().checkName(Names.NAT_SEMIRING);
  }

  private static boolean canApply(List<Monomial> hintLeftNF, List<Monomial> currentNF) {
    if (hintLeftNF.isEmpty()) return false;
    if (hintLeftNF.equals(currentNF)) return true;
    Pair<List<Monomial>, List<Monomial>> divRem = Monomial.divideAndRemainder(currentNF, hintLeftNF);
    return !divRem.proj1.isEmpty();
  }
}
