package org.arend.core.context.binding;

import org.arend.core.context.binding.inference.InferenceLevelVariable;
import org.arend.ext.core.ops.CMP;

public class ParamLevelVariable implements LevelVariable {
  private final String myName;
  private final int myIndex;
  private final int mySize;

  public ParamLevelVariable(String name, int index, int size) {
    myName = name;
    myIndex = index;
    mySize = size;
  }

  @Override
  public LevelVariable max(LevelVariable other) {
    return other instanceof InferenceLevelVariable ? null : !(other instanceof ParamLevelVariable) || mySize >= ((ParamLevelVariable) other).mySize ? this : other;
  }

  @Override
  public LevelVariable min(LevelVariable other) {
    return other instanceof InferenceLevelVariable ? null : !(other instanceof ParamLevelVariable) || mySize <= ((ParamLevelVariable) other).mySize ? this : other;
  }

  private static boolean compare(int n1, int n2, CMP cmp) {
    return cmp == CMP.LE ? n1 <= n2 : cmp == CMP.GE ? n1 >= n2 : n1 == n2;
  }

  @Override
  public boolean compare(LevelVariable other, CMP cmp) {
    return other == PVAR && (mySize == 0 || cmp == CMP.GE) || other instanceof ParamLevelVariable && compare(mySize, ((ParamLevelVariable) other).mySize, cmp);
  }

  public int getIndex() {
    return myIndex;
  }

  public int getSize() {
    return mySize;
  }

  @Override
  public String toString() {
    return myName;
  }

  @Override
  public boolean equals(Object o) {
    return this == o || mySize == 0 && o == PVAR;
  }

  @Override
  public int hashCode() {
    return mySize == 0 ? PVAR.hashCode() : super.hashCode();
  }
}
