package org.arend.core.context.binding;

import org.arend.ext.core.ops.CMP;

public class SortLevelVariable implements LevelVariable {
  private final int myIndex;

  public SortLevelVariable(int index) {
    myIndex = index;
  }

  @Override
  public LevelVariable min(LevelVariable other) {
    return compare(other, CMP.EQ) ? this : null;
  }

  @Override
  public boolean compare(LevelVariable other, CMP cmp) {
    return equals(other);
  }

  public int getIndex() {
    return myIndex;
  }

  @Override
  public String toString() {
    return "lvl" + myIndex;
  }

  @Override
  public boolean equals(Object o) {
    return this == o || o instanceof SortLevelVariable && myIndex == ((SortLevelVariable) o).myIndex;
  }
}
