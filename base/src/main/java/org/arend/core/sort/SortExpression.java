package org.arend.core.sort;

import org.arend.core.context.binding.inference.InferenceVariable;
import org.arend.core.context.param.DependentLink;
import org.arend.core.expr.Expression;
import org.arend.core.expr.PiExpression;
import org.arend.core.expr.UniverseExpression;
import org.arend.ext.core.level.ConstLevel;
import org.arend.ext.core.level.LevelSubstitution;
import org.arend.ext.core.ops.CMP;
import org.arend.ext.core.ops.NormalizationMode;
import org.arend.ext.core.sort.*;
import org.arend.term.concrete.Concrete;
import org.arend.typechecking.implicitargs.equations.Equations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.*;

public sealed interface SortExpression extends CoreSortExpression permits SortExpression.Const, SortExpression.Var, SortExpression.InfVar, SortExpression.Max, SortExpression.Pi, SortExpression.Prev, SortExpression.Succ {
  @NotNull SortExpression subst(boolean isType, @NotNull Map<? extends DependentLink, ? extends Expression> arguments, @NotNull LevelSubstitution substitution);
  @NotNull Sort withInfLevel();
  boolean isInfinite();

  default @NotNull SortExpression subst(@NotNull LevelSubstitution substitution) {
    return subst(false, Collections.emptyMap(), substitution);
  }

  default @NotNull SortExpression simplify() {
    return subst(LevelSubstitution.EMPTY);
  }

  @Override
  @Nullable BigInteger getSortHLevel();

  @Override
  default boolean isProp() {
    BigInteger level = getSortHLevel();
    return level != null && level.compareTo(BigInteger.ZERO) < 0;
  }

  static boolean compare(SortExpression sortExpr1, SortExpression sortExpr2, CMP cmp, Equations equations, Concrete.SourceNode sourceNode) {
    if (sortExpr1 instanceof Const(Sort sort1) && (cmp == CMP.LE && sort1.isProp() || cmp == CMP.GE && sort1.isOmega()) || sortExpr2 instanceof Const(Sort sort2) && (cmp == CMP.LE && sort2.isOmega() || cmp == CMP.GE && sort2.isProp()) || sortExpr1 instanceof Var && cmp == CMP.GE || sortExpr2 instanceof Var && cmp == CMP.LE) {
      return true;
    }

    switch (sortExpr1) {
      case Const(Sort sort1) when sortExpr2 instanceof Const(Sort sort2) -> {
        return Sort.compare(sort1, sort2, cmp, equations, sourceNode);
      }
      case Var var1 -> {
        if (sortExpr2 instanceof Var(DependentLink param2)) {
          return var1.parameter == param2;
        } else if (sortExpr2 instanceof Const(Sort sort2) && sort2.getPLevel().isClosed()) {
          return sort2.getPLevel().isInfinity() && sort2.getHLevel().isInfinity();
        }
      }
      default -> {}
    }
    return equations.addEquation(sortExpr1, sortExpr2, cmp, sourceNode);
  }

  record Const(@NotNull Sort sort) implements SortExpression, ConstSortExpression {
    @Override
    public @NotNull Sort getSort() {
      return sort;
    }

    @Override
    public @NotNull SortExpression subst(boolean isType, @NotNull Map<? extends DependentLink, ? extends Expression> arguments, @NotNull LevelSubstitution substitution) {
      return new Const(sort.subst(substitution));
    }

    @Override
    public @NotNull Sort withInfLevel() {
      return sort;
    }

    @Override
    public boolean isInfinite() {
      return sort.getPLevel().isInfinity();
    }

    @Override
    public @NotNull SortExpression simplify() {
      return this;
    }

    @Override
    public @Nullable BigInteger getSortHLevel() {
      ConstLevel level = sort.getHLevel();
      return level.value() == null ? null : level.value();
    }
  }

  record Var(DependentLink parameter) implements SortExpression {
    static SortExpression getCodomainSort(Expression arg) {
      if (arg == null) return new Const(Sort.INFINITY);
      arg = arg.normalize(NormalizationMode.WHNF).getType().normalize(NormalizationMode.WHNF);
      while (arg instanceof PiExpression piExpr) {
        arg = piExpr.getCodomain().normalize(NormalizationMode.WHNF);
      }
      SortExpression result = arg.toSortExpression();
      return result == null ? new Const(Sort.INFINITY) : result;
    }

    @Override
    public @NotNull SortExpression subst(boolean isType, @NotNull Map<? extends DependentLink, ? extends Expression> arguments, @NotNull LevelSubstitution substitution) {
      Expression arg = arguments.get(parameter);
      if (arg == null) return this;

      if (isType) {
        SortExpression result = arg instanceof UniverseExpression universe ? universe.getSortExpression() : null;
        return result == null ? this : result;
      } else {
        return getCodomainSort(arg);
      }
    }

    @Override
    public @NotNull Sort withInfLevel() {
      return Sort.INFINITY;
    }

    @Override
    public boolean isInfinite() {
      return true;
    }

    @Override
    public @Nullable BigInteger getSortHLevel() {
      return null;
    }
  }

  final class InfVar implements SortExpression {
    private final InferenceVariable variable;
    private final boolean isSelfVar;
    private SortExpression sort;

    public InfVar(InferenceVariable variable, boolean isSelfVar) {
      this.variable = variable;
      this.isSelfVar = isSelfVar;
    }

    public InfVar(InferenceVariable variable) {
      this(variable, false);
    }

    public InferenceVariable getVariable() {
      return variable;
    }

    private void checkIfSolved() {
      if (sort == null) {
        Expression expr = variable.getSolution();
        if (expr != null) {
          if (isSelfVar) {
            expr = expr.normalize(NormalizationMode.WHNF);
            if (expr instanceof UniverseExpression universe) {
              sort = universe.getSortExpression();
            }
          } else {
            Expression type = expr.getType();
            if (type != null) {
              type = type.normalize(NormalizationMode.WHNF);
              while (type instanceof PiExpression pi) {
                type = pi.getCodomain().normalize(NormalizationMode.WHNF);
              }
              if (type instanceof UniverseExpression universe) {
                sort = universe.getSortExpression();
              }
            }
          }
          if (sort == null) {
            sort = this;
          }
        }
      }
    }

    @Override
    public @NotNull SortExpression subst(boolean isType, @NotNull Map<? extends DependentLink, ? extends Expression> arguments, @NotNull LevelSubstitution substitution) {
      return sort == null || sort == this ? this : sort.subst(isType, arguments, substitution);
    }

    @Override
    public @NotNull Sort withInfLevel() {
      checkIfSolved();
      return sort == null || sort == this ? Sort.INFINITY : sort.withInfLevel();
    }

    @Override
    public boolean isInfinite() {
      return false;
    }

    @Override
    public @Nullable BigInteger getSortHLevel() {
      return sort == null || sort == this ? null : sort.getSortHLevel();
    }
  }

  static @NotNull SortExpression makeMax(@NotNull List<SortExpression> sorts) {
    if (sorts.isEmpty()) return new Const(Sort.PROP);
    if (sorts.size() == 1) return sorts.getFirst();

    List<SortExpression> newSorts = new ArrayList<>(sorts.size());
    Sort result = Sort.PROP;
    for (SortExpression aSort : sorts) {
      Sort newResult = aSort instanceof Const(Sort sort) ? result.max(sort) : null;
      if (newResult != null) {
        result = newResult;
      } else if (aSort instanceof Max maxSort) {
        for (SortExpression bSort : maxSort.mySorts) {
          Sort newResult2 = bSort instanceof Const(Sort sort) ? result.max(sort) : null;
          if (newResult2 != null) {
            result = newResult2;
          } else {
            if (newSorts.isEmpty() || !newSorts.getLast().equals(bSort)) newSorts.add(bSort);
          }
        }
      } else {
        if (newSorts.isEmpty() || !newSorts.getLast().equals(aSort)) newSorts.add(aSort);
      }
    }

    if (newSorts.isEmpty()) return new Const(result);
    if (result.isProp()) return newSorts.size() == 1 ? newSorts.getFirst() : new Max(newSorts);
    newSorts.add(new Const(result));
    return new Max(newSorts);
  }

  static @NotNull SortExpression makePi(@NotNull SortExpression domain, @NotNull SortExpression codomain) {
    if (codomain.isProp()) {
      return codomain;
    }
    if (domain instanceof Const(Sort sort1)) {
      if (sort1.getPLevel().isZero()) {
        return codomain;
      }
      if (codomain instanceof Const(Sort sort2)) {
        return new Const(PiExpression.piSort(sort1, sort2));
      }
    }
    return domain.equals(codomain) ? codomain : new Pi(domain, codomain);
  }

  final class Max implements SortExpression, MaxSortExpression {
    private final List<SortExpression> mySorts;

    private Max(List<SortExpression> sorts) {
      mySorts = sorts;
    }

    @Override
    public @NotNull List<SortExpression> getSorts() {
      return mySorts;
    }

    @Override
    public @NotNull SortExpression subst(boolean isType, @NotNull Map<? extends DependentLink, ? extends Expression> arguments, @NotNull LevelSubstitution substitution) {
      List<SortExpression> sorts = new ArrayList<>(mySorts.size());
      for (SortExpression sort : mySorts) {
        sorts.add(sort.subst(isType, arguments, substitution));
      }
      return makeMax(sorts);
    }

    @Override
    public @NotNull Sort withInfLevel() {
      Sort result = Sort.PROP;
      for (SortExpression sort : mySorts) {
        result = result.max(sort.withInfLevel());
      }
      return result;
    }

    @Override
    public boolean isInfinite() {
      for (SortExpression sort : mySorts) {
        if (sort.isInfinite()) return true;
      }
      return false;
    }

    @Override
    public @Nullable BigInteger getSortHLevel() {
      BigInteger result = BigInteger.ONE.negate();
      for (SortExpression sort : mySorts) {
        BigInteger level = sort.getSortHLevel();
        if (level == null) return null;
        result = result.max(level);
      }
      return result;
    }
  }

  static @NotNull SortExpression makeTrunc(@NotNull SortExpression sort, BigInteger level) {
    return makePi(sort, new Const(new Sort(new Level(BigInteger.ZERO), new ConstLevel(level))));
  }

  static @NotNull SortExpression makePrev(@NotNull SortExpression sort) {
    if (sort instanceof Const(Sort aSort)) {
      if (aSort.isProp() || aSort.isSet()) return new Const(Sort.PROP);
      ConstLevel hLevel = aSort.getHLevel();
      if (hLevel.isInfinity()) return sort;
      return new Const(new Sort(aSort.getPLevel(), new ConstLevel(hLevel.value().subtract(BigInteger.ONE))));
    }
    return new Prev(sort);
  }

  static @NotNull SortExpression makeSucc(@NotNull SortExpression sort) {
    return sort instanceof Const(Sort aSort) ? new Const(aSort.succ()) : sort instanceof Var ? new Const(Sort.INFINITY) : new Succ(sort);
  }

  final class Pi implements SortExpression, PiSortExpression {
    private final SortExpression myDomain;
    private final SortExpression myCodomain;

    private Pi(SortExpression domain, SortExpression codomain) {
      myDomain = domain;
      myCodomain = codomain;
    }

    @Override
    public @NotNull SortExpression getDomain() {
      return myDomain;
    }

    @Override
    public @NotNull SortExpression getCodomain() {
      return myCodomain;
    }

    @Override
    public @NotNull SortExpression subst(boolean isType, @NotNull Map<? extends DependentLink, ? extends Expression> arguments, @NotNull LevelSubstitution substitution) {
      return makePi(myDomain.subst(isType, arguments, substitution), myCodomain.subst(isType, arguments, substitution));
    }

    @Override
    public @NotNull Sort withInfLevel() {
      Sort domain = myDomain.withInfLevel();
      Sort codomain = myCodomain.withInfLevel();
      return PiExpression.piSort(domain, codomain);
    }

    @Override
    public boolean isInfinite() {
      return myDomain.isInfinite() || myCodomain.isInfinite();
    }

    @Override
    public @Nullable BigInteger getSortHLevel() {
      return myCodomain.getSortHLevel();
    }
  }

  final class Prev implements SortExpression, PreviousSortExpression {
    private final SortExpression mySort;

    public Prev(SortExpression sort) {
      mySort = sort;
    }

    @Override
    public @NotNull SortExpression getSort() {
      return mySort;
    }

    @Override
    public @NotNull SortExpression subst(boolean isType, @NotNull Map<? extends DependentLink, ? extends Expression> arguments, @NotNull LevelSubstitution substitution) {
      return makePrev(mySort.subst(isType, arguments, substitution));
    }

    @Override
    public @NotNull Sort withInfLevel() {
      Sort result = mySort.withInfLevel();
      return result.isSet() || result.isProp() ? Sort.PROP : result.getHLevel().isInfinity() ? result : new Sort(result.getPLevel(), new ConstLevel(result.getHLevel().value().subtract(BigInteger.ONE)));
    }

    @Override
    public boolean isInfinite() {
      return mySort.isInfinite();
    }

    @Override
    public @Nullable BigInteger getSortHLevel() {
      BigInteger level = mySort.getSortHLevel();
      return level == null ? null : level.compareTo(BigInteger.ZERO) < 0 ? level : level.subtract(BigInteger.ONE);
    }
  }

  final class Succ implements SortExpression, NextSortExpression {
    private final SortExpression mySort;

    public Succ(SortExpression sort) {
      mySort = sort;
    }

    @Override
    public @NotNull SortExpression getSort() {
      return mySort;
    }

    @Override
    public @NotNull SortExpression subst(boolean isType, @NotNull Map<? extends DependentLink, ? extends Expression> arguments, @NotNull LevelSubstitution substitution) {
      return makeSucc(mySort.subst(isType, arguments, substitution));
    }

    @Override
    public @NotNull Sort withInfLevel() {
      return mySort.withInfLevel().succ();
    }

    @Override
    public boolean isInfinite() {
      return mySort.isInfinite();
    }

    @Override
    public @Nullable BigInteger getSortHLevel() {
      BigInteger level = mySort.getSortHLevel();
      return level == null ? null : level.add(BigInteger.ONE);
    }
  }
}
