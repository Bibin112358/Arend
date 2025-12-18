package org.arend.core.sort;

import org.arend.core.context.binding.LevelVariable;
import org.arend.core.context.binding.inference.InferenceLevelVariable;
import org.arend.core.context.binding.inference.InferenceVariable;
import org.arend.core.definition.ClassField;
import org.arend.core.expr.Expression;
import org.arend.core.expr.PiExpression;
import org.arend.core.expr.UniverseExpression;
import org.arend.ext.core.level.LevelSubstitution;
import org.arend.ext.core.ops.CMP;
import org.arend.ext.core.ops.NormalizationMode;
import org.arend.ext.core.sort.*;
import org.arend.naming.reference.FieldReferableImpl;
import org.arend.term.concrete.Concrete;
import org.arend.typechecking.implicitargs.equations.Equations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.*;

public sealed interface SortExpression extends CoreSortExpression permits SortExpression.Const, SortExpression.Field, SortExpression.InfVar, SortExpression.Max, SortExpression.Pi, SortExpression.Prev, SortExpression.Succ, SortExpression.Var {
  @NotNull SortExpression subst(boolean isType, @NotNull List<? extends Expression> arguments, @NotNull Map<? extends ClassField, ? extends Expression> fields, @NotNull LevelSubstitution substitution);
  @NotNull Sort withInfLevel();
  boolean isInfinite();

  default @NotNull SortExpression subst(boolean isType, @NotNull List<? extends Expression> arguments, @NotNull LevelSubstitution substitution) {
    return subst(isType, arguments, Collections.emptyMap(), substitution);
  }

  default @NotNull SortExpression subst(@NotNull LevelSubstitution substitution) {
    return subst(false, Collections.emptyList(), substitution);
  }

  default @NotNull SortExpression simplify() {
    return subst(LevelSubstitution.EMPTY);
  }

  @Override
  default @Nullable BigInteger getSortHLevel() {
    SortExpression simplified = simplify();
    if (!(simplified instanceof Const(Sort sort))) return null;
    Level level = sort.getHLevel();
    return level.isClosed() ? BigInteger.valueOf(level.getConstant()) : null;
  }

  @Override
  default boolean isProp() {
    BigInteger level = getSortHLevel();
    return level != null && level.compareTo(BigInteger.ZERO) < 0;
  }

  static boolean compare(SortExpression sortExpr1, SortExpression sortExpr2, CMP cmp, Equations equations, Concrete.SourceNode sourceNode) {
    if (sortExpr1 instanceof SortExpression.Const(Sort sort1) && (cmp == CMP.LE && sort1.isProp() || cmp == CMP.GE && sort1.isOmega()) || sortExpr2 instanceof SortExpression.Const(Sort sort2) && (cmp == CMP.LE && sort2.isOmega() || cmp == CMP.GE && sort2.isProp()) || (sortExpr1 instanceof SortExpression.Field || sortExpr1 instanceof SortExpression.Var) && cmp == CMP.GE || (sortExpr2 instanceof SortExpression.Field || sortExpr2 instanceof SortExpression.Var) && cmp == CMP.LE) {
      return true;
    }

    return switch (sortExpr1) {
      case Const(Sort sort1) when sortExpr2 instanceof Const(Sort sort2) -> Sort.compare(sort1, sort2, cmp, equations, sourceNode);
      case Var(int index1) when sortExpr2 instanceof Var(int index2) -> index1 == index2;
      case Field(FieldReferableImpl field1) when sortExpr2 instanceof Field(FieldReferableImpl field2) -> field1.equals(field2);
      case null, default -> equations.addEquation(sortExpr1, sortExpr2, cmp, sourceNode);
    };
  }

  record Const(@NotNull Sort sort) implements SortExpression, ConstSortExpression {
    @Override
    public @NotNull Sort getSort() {
      return sort;
    }

    @Override
    public @NotNull SortExpression subst(boolean isType, @NotNull List<? extends Expression> arguments, @NotNull Map<? extends ClassField, ? extends Expression> fields, @NotNull LevelSubstitution substitution) {
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
  }

  record Var(int index) implements SortExpression {
    private static SortExpression getCodomainSort(Expression arg) {
      if (arg == null) return new Const(Sort.INFINITY);
      arg = arg.normalize(NormalizationMode.WHNF).getType().normalize(NormalizationMode.WHNF);
      while (arg instanceof PiExpression piExpr) {
        arg = piExpr.getCodomain().normalize(NormalizationMode.WHNF);
      }
      SortExpression result = arg.toSortExpression();
      return result == null ? new Const(Sort.INFINITY) : result;
    }

    @Override
    public @NotNull SortExpression subst(boolean isType, @NotNull List<? extends Expression> arguments, @NotNull Map<? extends ClassField, ? extends Expression> fields, @NotNull LevelSubstitution substitution) {
      if (index >= arguments.size()) return this;

      if (isType) {
        SortExpression result = arguments.get(index) instanceof UniverseExpression universe ? universe.getSortExpression() : null;
        return result == null ? this : result;
      } else {
        return getCodomainSort(arguments.get(index));
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
  }

  record Field(FieldReferableImpl field) implements SortExpression {
    @Override
    public @NotNull SortExpression subst(boolean isType, @NotNull List<? extends Expression> arguments, @NotNull Map<? extends ClassField, ? extends Expression> fields, @NotNull LevelSubstitution substitution) {
      if (!(field.getTypechecked() instanceof ClassField classField)) return this;
      Expression arg = fields.get(classField);
      if (arg == null) return this;

      if (isType) {
        SortExpression result = arg instanceof UniverseExpression universe ? universe.getSortExpression() : null;
        return result == null ? this : result;
      } else {
        return Var.getCodomainSort(arg);
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
  }

  final class InfVar implements SortExpression {
    private final InferenceVariable variable;
    private SortExpression sort;

    public InfVar(InferenceVariable variable) {
      this.variable = variable;
    }

    public InferenceVariable getVariable() {
      return variable;
    }

    private void checkIfSolved() {
      if (sort == null) {
        Expression expr = variable.getSolution();
        if (expr != null) {
          sort = expr.getSortExpressionOfType();
          if (sort == null) {
            sort = this;
          }
        }
      }
    }

    @Override
    public @NotNull SortExpression subst(boolean isType, @NotNull List<? extends Expression> arguments, @NotNull Map<? extends ClassField, ? extends Expression> fields, @NotNull LevelSubstitution substitution) {
      return sort == null || sort == this ? this : sort.subst(isType, arguments, fields, substitution);
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

  static @NotNull SortExpression makePi(@NotNull List<SortExpression> domainSorts, @NotNull SortExpression codomain) {
    SortExpression domain = makeMax(domainSorts);
    if (domain instanceof Const(Sort sort1)) {
      if (sort1.getPLevel().isClosed() && sort1.getPLevel().getConstant() == 0) {
        return codomain;
      }
      if (codomain instanceof Const(Sort sort2)) {
        return new Const(PiExpression.piSort(sort1, sort2));
      }
    }
    return domain.equals(codomain) ? codomain : domain instanceof Max maxSort ? new Pi(maxSort.mySorts, codomain) : new Pi(Collections.singletonList(domain), codomain);
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
    public @NotNull SortExpression subst(boolean isType, @NotNull List<? extends Expression> arguments, @NotNull Map<? extends ClassField, ? extends Expression> fields, @NotNull LevelSubstitution substitution) {
      List<SortExpression> sorts = new ArrayList<>(mySorts.size());
      for (SortExpression sort : mySorts) {
        sorts.add(sort.subst(isType, arguments, fields, substitution));
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
  }

  static @NotNull SortExpression makePrev(@NotNull SortExpression sort) {
    if (sort instanceof Const(Sort aSort)) {
      if (aSort.isProp() || aSort.isSet()) return new Const(Sort.PROP);
      Level hLevel = aSort.getHLevel();
      if (hLevel.isInfinity()) return sort;
      if (hLevel.isClosed()) return new Const(new Sort(aSort.getPLevel(), new Level(hLevel.getConstant() - 1)));

      Map<LevelVariable, Integer> newVars = new HashMap<>();
      for (Map.Entry<LevelVariable, Integer> entry : hLevel.getVarPairs()) {
        if (entry.getValue() > 0) {
          newVars.put(entry.getKey(), entry.getValue() - 1);
        } else if (!(entry.getKey() instanceof InferenceLevelVariable)) {
          newVars.put(entry.getKey(), entry.getValue());
        } else {
          return new Prev(sort);
        }
      }

      return new Const(new Sort(aSort.getPLevel(), new Level(newVars, hLevel.getConstant() >= 0 ? hLevel.getConstant() - 1 : -1)));
    }
    return new Prev(sort);
  }

  static @NotNull SortExpression makeSucc(@NotNull SortExpression sort) {
    return sort instanceof Const(Sort aSort) ? new Const(aSort.succ()) : sort instanceof Var || sort instanceof Field ? new Const(Sort.INFINITY) : new Succ(sort);
  }

  final class Pi implements SortExpression, PiSortExpression {
    private final List<SortExpression> myDomain;
    private final SortExpression myCodomain;

    private Pi(List<SortExpression> domain, SortExpression codomain) {
      myDomain = domain;
      myCodomain = codomain;
    }

    @Override
    public @NotNull List<SortExpression> getDomain() {
      return myDomain;
    }

    @Override
    public @NotNull SortExpression getCodomain() {
      return myCodomain;
    }

    @Override
    public @NotNull SortExpression subst(boolean isType, @NotNull List<? extends Expression> arguments, @NotNull Map<? extends ClassField, ? extends Expression> fields, @NotNull LevelSubstitution substitution) {
      List<SortExpression> domain = new ArrayList<>(myDomain.size());
      for (SortExpression sort : myDomain) {
        domain.add(sort.subst(isType, arguments, fields, substitution));
      }
      return makePi(domain, myCodomain.subst(isType, arguments, fields, substitution));
    }

    @Override
    public @NotNull Sort withInfLevel() {
      Sort domain = new Max(myDomain).withInfLevel();
      Sort codomain = myCodomain.withInfLevel();
      return PiExpression.piSort(domain, codomain);
    }

    @Override
    public boolean isInfinite() {
      for (SortExpression sort : myDomain) {
        if (sort.isInfinite()) return true;
      }
      return myCodomain.isInfinite();
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
    public @NotNull SortExpression subst(boolean isType, @NotNull List<? extends Expression> arguments, @NotNull Map<? extends ClassField, ? extends Expression> fields, @NotNull LevelSubstitution substitution) {
      return makePrev(mySort.subst(isType, arguments, fields, substitution));
    }

    @Override
    public @NotNull Sort withInfLevel() {
      Sort result = mySort.withInfLevel();
      return result.isSet() || result.isProp() ? Sort.PROP : result.getHLevel().isInfinity() || !result.getHLevel().isClosed() ? result : new Sort(result.getPLevel(), new Level(result.getHLevel().getConstant() - 1));
    }

    @Override
    public boolean isInfinite() {
      return mySort.isInfinite();
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
    public @NotNull SortExpression subst(boolean isType, @NotNull List<? extends Expression> arguments, @NotNull Map<? extends ClassField, ? extends Expression> fields, @NotNull LevelSubstitution substitution) {
      return makeSucc(mySort.subst(isType, arguments, fields, substitution));
    }

    @Override
    public @NotNull Sort withInfLevel() {
      return mySort.withInfLevel().succ();
    }

    @Override
    public boolean isInfinite() {
      return mySort.isInfinite();
    }
  }
}
