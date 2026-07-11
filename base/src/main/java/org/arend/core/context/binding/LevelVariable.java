package org.arend.core.context.binding;

import org.arend.ext.core.ops.CMP;
import org.arend.ext.variable.Variable;

import java.util.List;

public interface LevelVariable extends Variable {
  LevelVariable min(LevelVariable other);
  boolean compare(LevelVariable other, CMP cmp);

  @Override
  default String getName() {
    return toString();
  }

  static boolean compare(List<? extends LevelVariable> vars1, List<? extends LevelVariable> vars2, CMP cmp) {
    if (vars1.size() != vars2.size()) {
      return false;
    }
    for (int i = 0; i < vars1.size(); i++) {
      if (!vars1.get(i).compare(vars2.get(i), cmp)) {
        return false;
      }
    }
    return true;
  }

  // TODO[sorts]: Delete this
  LevelVariable PVAR = new LevelVariable() {
    @Override
    public LevelVariable min(LevelVariable other) {
      return this == other ? this : null;
    }

    @Override
    public boolean compare(LevelVariable other, CMP cmp) {
      return this == other;
    }

    @Override
    public String toString() {
      return "\\lp";
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof LevelVariable && compare((LevelVariable) o, CMP.EQ);
    }
  };
}
