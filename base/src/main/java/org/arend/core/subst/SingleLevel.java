package org.arend.core.subst;

import org.arend.core.context.binding.LevelVariable;
import org.arend.core.context.binding.inference.InferenceLevelVariable;
import org.arend.core.definition.Definition;
import org.arend.ext.core.level.LevelSubstitution;
import org.arend.ext.core.ops.CMP;
import org.arend.ext.variable.Variable;
import org.arend.core.sort.Level;
import org.arend.term.concrete.Concrete;
import org.arend.typechecking.implicitargs.equations.DummyEquations;
import org.arend.typechecking.implicitargs.equations.Equations;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;

public class SingleLevel implements LevelSubstitution, Levels {
  public static final SingleLevel STD = new SingleLevel(new Level(LevelVariable.PVAR));
  public static final SingleLevel ZERO = new SingleLevel(new Level(BigInteger.ZERO));

  private final Level myLevel;

  public SingleLevel(Level pLevel) {
    this.myLevel = pLevel;
  }

  @Override
  public boolean isEmpty() {
    return LevelVariable.PVAR.equals(myLevel.getSingleVar());
  }

  @Override
  public int size() {
    return 1;
  }

  @Override
  public boolean isClosed() {
    return myLevel.isClosed();
  }

  public Level getLevel() {
    return myLevel;
  }

  @Override
  public Level get(Variable variable) {
    return LevelVariable.PVAR.equals(variable) ? myLevel : null;
  }

  @Override
  public SingleLevel subst(LevelSubstitution substitution) {
    return isClosed() ? this : new SingleLevel(myLevel.subst(substitution));
  }

  @Override
  public boolean compare(Levels other, CMP cmp, Equations equations, Concrete.SourceNode sourceNode) {
    return other instanceof SingleLevel && Level.compare(myLevel, ((SingleLevel) other).myLevel, cmp, equations, sourceNode);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Levels && compare((Levels) other, CMP.EQ, DummyEquations.getInstance(), null);
  }

  public static SingleLevel generateInferVars(Equations equations, boolean isUniverseLike, Concrete.SourceNode sourceNode) {
    InferenceLevelVariable pl = new InferenceLevelVariable(isUniverseLike, sourceNode);
    equations.addVariable(pl);
    return new SingleLevel(new Level(pl));
  }

  @Override
  public LevelSubstitution makeSubstitution(@NotNull Definition definition) {
    return this;
  }

  @Override
  public List<? extends Level> toList() {
    return Collections.singletonList(myLevel);
  }
}
