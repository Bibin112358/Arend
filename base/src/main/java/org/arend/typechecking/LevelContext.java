package org.arend.typechecking;

import org.arend.core.context.binding.LevelVariable;
import org.arend.naming.reference.LevelReferable;

import java.util.Map;

public class LevelContext {
  private final Map<LevelReferable, LevelVariable> myVariables;
  public final boolean isPBased;

  public LevelContext(Map<LevelReferable, LevelVariable> variables, boolean isPBased) {
    myVariables = variables;
    this.isPBased = isPBased;
  }

  public Map<LevelReferable, LevelVariable> getVariables() {
    return myVariables;
  }

  public LevelVariable getVariable(LevelReferable ref) {
    return myVariables.get(ref);
  }
}
