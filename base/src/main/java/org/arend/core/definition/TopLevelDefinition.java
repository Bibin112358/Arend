package org.arend.core.definition;

import org.arend.core.context.binding.LevelVariable;
import org.arend.core.context.param.DependentLink;
import org.arend.ext.util.Pair;
import org.arend.naming.reference.LocatedReferable;
import org.arend.naming.reference.TCDefReferable;

import java.util.*;

public abstract class TopLevelDefinition extends CallableDefinition {
  private UniverseKind myUniverseKind = UniverseKind.NO_UNIVERSES;
  private List<? extends LevelVariable> myLevelParameters;
  private LocatedReferable myLevelsParent;
  private boolean myLevelsDerived;
  private List<Pair<TCDefReferable,Integer>> myParametersOriginalDefinitions = Collections.emptyList();
  private Set<? extends FunctionDefinition> myAxioms = Collections.emptySet();
  private Set<? extends Definition> myGoals = Collections.emptySet();

  public TopLevelDefinition(TCDefReferable referable, TypeCheckingStatus status) {
    super(referable, status);
  }

  @Override
  public TopLevelDefinition getTopLevelDefinition() {
    return this;
  }

  @Override
  public UniverseKind getUniverseKind() {
    return myUniverseKind;
  }

  public void setUniverseKind(UniverseKind kind) {
    myUniverseKind = kind;
  }

  @Override
  public List<? extends LevelVariable> getLevelParameters() {
    return myLevelParameters;
  }

  public void setLevelParameters(List<LevelVariable> parameters) {
    myLevelParameters = parameters;
  }

  protected static boolean hasInfiniteParameters(DependentLink param) {
    for (; param.hasNext(); param = param.getNext()) {
      param = param.getNextTyped(null);
      if (param.getType().isInfinityLevel()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public LocatedReferable getLevelsParent() {
    return myLevelsParent;
  }

  public void setLevelsParent(LocatedReferable parent) {
    myLevelsParent = parent;
  }

  @Override
  public boolean areLevelsDerived() {
    return myLevelsDerived;
  }

  public void setLevelsDerived(boolean derived) {
    myLevelsDerived = derived;
  }

  @Override
  public List<? extends Pair<TCDefReferable,Integer>> getParametersOriginalDefinitions() {
    return myParametersOriginalDefinitions;
  }

  public void setParametersOriginalDefinitions(List<Pair<TCDefReferable,Integer>> definitions) {
    myParametersOriginalDefinitions = definitions;
  }

  @Override
  public Set<? extends FunctionDefinition> getAxioms() {
    return myAxioms;
  }

  public boolean isAxiom() {
    return false;
  }

  public void setAxioms(Set<? extends FunctionDefinition> axioms) {
    myAxioms = axioms;
  }

  @Override
  public Set<? extends Definition> getGoals() {
    return myGoals;
  }

  public void setGoals(Set<? extends Definition> goals) {
    myGoals = goals;
  }
}
