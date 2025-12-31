package org.arend.core.context.binding.inference;

import org.arend.core.context.binding.LevelVariable;
import org.arend.ext.core.ops.CMP;
import org.arend.term.concrete.Concrete;

public class InferenceLevelVariable implements LevelVariable {
  private final boolean myUniverseLike;
  private final Concrete.SourceNode mySourceNode;

  public InferenceLevelVariable(boolean isUniverseLike, Concrete.SourceNode sourceNode) {
    myUniverseLike = isUniverseLike;
    mySourceNode = sourceNode;
  }

  @Override
  public LevelVariable max(LevelVariable other) {
    return this == other ? this : null;
  }

  @Override
  public LevelVariable min(LevelVariable other) {
    return this == other ? this : null;
  }

  @Override
  public boolean compare(LevelVariable other, CMP cmp) {
    return this == other;
  }

  public boolean isUniverseLike() {
    return myUniverseLike;
  }

  public Concrete.SourceNode getSourceNode() {
    return mySourceNode;
  }

  @Override
  public String toString() {
    return "?p";
  }
}
