package org.arend.typechecking.implicitargs.equations;

import org.arend.core.context.binding.LevelVariable;
import org.arend.core.context.binding.inference.InferenceLevelVariable;
import org.arend.core.sort.Level;
import org.arend.ext.core.level.LevelSubstitution;
import org.arend.core.subst.SimpleLevelSubstitution;
import org.arend.ext.core.ops.CMP;
import org.arend.ext.error.ErrorReporter;
import org.arend.typechecking.error.local.ConstantSolveLevelEquationError;
import org.arend.typechecking.error.local.SolveLevelEquationsError;
import org.arend.typechecking.dfs.DFS;
import org.arend.typechecking.dfs.MapDFS;
import org.arend.ext.util.Pair;

import java.util.*;

public class LevelEquationsSolver {
  private final List<AbstractEquation<Level>> myDeferredMaxEquations;
  private final LevelEquations<InferenceLevelVariable> myPLevelEquations = new LevelEquations<>();      // equations of the forms      c <= ?y and ?x <= max(?y + c', d)
  private final LevelEquations<InferenceLevelVariable> myBasedPLevelEquations = new LevelEquations<>(); // equations of the forms lp + c <= ?y and ?x <= max(?y + c', d)
  private final Map<InferenceLevelVariable, Level> myConstantUpperBounds = new HashMap<>();
  private final Map<InferenceLevelVariable, Set<LevelVariable>> myLowerBounds = new HashMap<>();
  private final Map<LevelVariable, Set<InferenceLevelVariable>> myUpperBounds = new HashMap<>();
  private final ErrorReporter myErrorReporter;
  private final boolean myPBased;

  public LevelEquationsSolver(List<LevelEquation<LevelVariable>> levelEquations, List<? extends AbstractEquation<Level>> deferredMaxEquations, List<InferenceLevelVariable> variables, ErrorReporter errorReporter, boolean pBased) {
    myDeferredMaxEquations = new ArrayList<>(deferredMaxEquations);
    myPBased = pBased;
    for (InferenceLevelVariable var : variables) {
      myPLevelEquations.addVariable(var);
      if (pBased) {
        myBasedPLevelEquations.addVariable(var);
      }
    }
    variables.clear();

    for (LevelEquation<LevelVariable> levelEquation : levelEquations) {
      if (levelEquation.isInfinity()) {
        //noinspection unchecked
        addEquation((LevelEquation<InferenceLevelVariable>) (LevelEquation<?>) levelEquation, false);
      } else {
        addLevelEquation(levelEquation.getVariable1(), levelEquation.getVariable2(), levelEquation.getConstant(), levelEquation.getMaxConstant());
      }
    }

    myErrorReporter = errorReporter;
  }

  private void addLevelEquation(final LevelVariable var1, LevelVariable var2, int constant, int maxConstant) {
    // 0 <= max(_ +-c, +-d) // 10
    if (var1 == null) {
      // 0 <= max(?y - c, -d) // 1
      if (maxConstant < 0 && constant < 0) {
        addEquation(new LevelEquation<>(null, (InferenceLevelVariable) var2, constant), false);
      }
      return;
    }

    if (var2 instanceof InferenceLevelVariable && var1 != var2) {
      myLowerBounds.computeIfAbsent((InferenceLevelVariable) var2, k -> new HashSet<>()).add(var1);
      myUpperBounds.computeIfAbsent(var1, k -> new HashSet<>()).add((InferenceLevelVariable) var2);
    }

    // ?x <= max(_ +- c, +-d) // 10
    if (var1 instanceof InferenceLevelVariable) {
      // ?x <= max(?y +- c, +-d) // 4
      if (var2 instanceof InferenceLevelVariable) {
        LevelEquation<InferenceLevelVariable> equation = new LevelEquation<>((InferenceLevelVariable) var1, (InferenceLevelVariable) var2, constant, maxConstant < 0 ? null : maxConstant);
        addEquation(equation, false);
        if (myPBased) {
          addEquation(equation, true);
        }
      } else {
        // ?x <= max(+-c, +-d), ?x <= max(l +- c, +-d) // 6
        Level oldLevel = myConstantUpperBounds.get(var1);
        if (oldLevel == null) {
          myConstantUpperBounds.put((InferenceLevelVariable) var1, new Level(var2, constant, maxConstant));
        } else {
          Map.Entry<LevelVariable,Integer> oldEntry = oldLevel.getVarPairs().isEmpty() ? null : oldLevel.getVarPairs().iterator().next();
          if (var2 == null && oldEntry != null || var2 != null && oldEntry == null) {
            int otherConstant = var2 == null ? Math.max(constant, maxConstant) : oldLevel.getConstant();
            int thisConst = var2 == null ? oldEntry.getValue() : constant;
            int thisMaxConst = var2 == null ? oldLevel.getConstant() : maxConstant;
            myConstantUpperBounds.put((InferenceLevelVariable) var1, new Level(Math.max(Math.min(thisMaxConst, otherConstant), Math.min(thisConst, otherConstant))));
          } else {
            if (var2 == null) {
              int newConst = Math.max(constant, maxConstant);
              if (newConst < oldLevel.getConstant()) {
                myConstantUpperBounds.put((InferenceLevelVariable) var1, new Level(newConst));
              }
            } else {
              myConstantUpperBounds.put((InferenceLevelVariable) var1, constant < 0 ? new Level(Math.min(maxConstant, oldLevel.getConstant())) : new Level(var2.min(oldEntry.getKey()), Math.min(constant, oldEntry.getValue()), Math.min(maxConstant, oldLevel.getConstant())));
            }
          }
        }
      }
      return;
    }

    // l <= max(?y +- c, +-d) // 4
    if (var2 instanceof InferenceLevelVariable && constant < 0) {
      addEquation(new LevelEquation<>(null, (InferenceLevelVariable) var2, constant), myPBased);
    }
  }

  private void addEquation(LevelEquation<InferenceLevelVariable> equation, boolean based) {
    InferenceLevelVariable var1 = equation.isInfinity() ? equation.getVariable() : equation.getVariable1();
    InferenceLevelVariable var2 = equation.isInfinity() ? equation.getVariable() : equation.getVariable2();

    if (var1 != null || var2 != null) {
      if (based) {
        myBasedPLevelEquations.addEquation(equation);
      } else {
        myPLevelEquations.addEquation(equation);
      }
    } else {
      throw new IllegalStateException();
    }
  }

  private LevelVariable getLowerBound(InferenceLevelVariable var) {
    return new DFS<InferenceLevelVariable,LevelVariable>() {
      @Override
      protected LevelVariable forDependencies(InferenceLevelVariable unit) {
        Set<LevelVariable> bounds = myLowerBounds.get(unit);
        LevelVariable result = LevelVariable.PVAR;
        if (bounds != null) {
          for (LevelVariable bound : bounds) {
            result = result.max(bound instanceof InferenceLevelVariable ? visit((InferenceLevelVariable) bound) : bound);
          }
        }
        return result;
      }

      @Override
      protected LevelVariable getVisitedValue(InferenceLevelVariable unit, boolean cycle) {
        return LevelVariable.PVAR;
      }
    }.visit(var);
  }

  public LevelSubstitution solveLevels() {
    List<LevelEquation<InferenceLevelVariable>> cycle = null;
    Map<InferenceLevelVariable, Integer> basedSolution = new HashMap<>();

    Set<InferenceLevelVariable> pUnBased = new HashSet<>();
    if (myPBased) {
      cycle = myBasedPLevelEquations.solve(basedSolution);
      calculateUnBased(myBasedPLevelEquations, pUnBased, basedSolution);
    }
    boolean ok = cycle == null;
    if (!ok) {
      reportCycle(cycle, pUnBased);
    }

    Map<InferenceLevelVariable, Integer> solution = new HashMap<>();
    cycle = myPLevelEquations.solve(solution);
    if (ok && cycle != null) {
      reportCycle(cycle, pUnBased);
    }

    Set<InferenceLevelVariable> unBased = myPBased ? pUnBased : new HashSet<>(myPLevelEquations.getVariables());
    SimpleLevelSubstitution result = new SimpleLevelSubstitution();
    for (InferenceLevelVariable var : unBased) {
      int sol = solution.get(var);
      result.add(var, sol == LevelEquations.INFINITY ? Level.INFINITY : new Level(-sol));
    }

    boolean useStd = true;
    loop:
    for (Set<LevelVariable> vars : myLowerBounds.values()) {
      for (LevelVariable var : vars) {
        if (!(var instanceof InferenceLevelVariable) && var != LevelVariable.PVAR) {
          useStd = false;
          break loop;
        }
      }
    }

    for (Map.Entry<InferenceLevelVariable, Integer> entry : basedSolution.entrySet()) {
      assert entry.getValue() != LevelEquations.INFINITY;
      if (!unBased.contains(entry.getKey())) {
        int sol = solution.get(entry.getKey());
        result.add(entry.getKey(), sol == LevelEquations.INFINITY || entry.getValue() == LevelEquations.INFINITY ? Level.INFINITY : new Level(useStd ? LevelVariable.PVAR : getLowerBound(entry.getKey()), -entry.getValue(), -sol));
      }
    }

    for (Map.Entry<InferenceLevelVariable, Level> entry : myConstantUpperBounds.entrySet()) {
      Level level = result.get(entry.getKey());
      if (!Level.compare(level, entry.getValue(), CMP.LE, DummyEquations.getInstance(), null)) {
        int maxConstant = entry.getValue().getConstant();
        List<LevelEquation<LevelVariable>> equations = new ArrayList<>(2);
        Map.Entry<LevelVariable,Integer> levelEntry = level.getVarPairs().isEmpty() ? null : level.getVarPairs().iterator().next();
        LevelVariable levelVar = levelEntry == null ? null : levelEntry.getKey();
        if (!Level.compare(level.withMaxConstant() ? new Level(levelVar, levelVar == null ? level.getConstant() : levelEntry.getValue()) : level, entry.getValue(), CMP.LE, DummyEquations.getInstance(), null)) {
          equations.add(level.isInfinity() ? new LevelEquation<>(entry.getKey()) : new LevelEquation<>(levelVar, entry.getKey(), -(levelEntry == null ? level.getConstant() : levelEntry.getValue())));
        }
        if (level.withMaxConstant() && !Level.compare(new Level(level.getConstant()), entry.getValue(), CMP.LE, DummyEquations.getInstance(), null)) {
          equations.add(new LevelEquation<>(null, entry.getKey(), -level.getConstant()));
        }
        Map.Entry<LevelVariable,Integer> entryEntry = entry.getValue().getVarPairs().isEmpty() ? null : entry.getValue().getVarPairs().iterator().next();
        equations.add(new LevelEquation<>(entry.getKey(), entryEntry == null ? null : entryEntry.getKey(), entryEntry == null ? entry.getValue().getConstant() : entryEntry.getValue(), maxConstant));
        myErrorReporter.report(new SolveLevelEquationsError(equations, entry.getKey().getSourceNode()));
      }
    }

    for (AbstractEquation<Level> equation : myDeferredMaxEquations) {
      if (!Level.compare(equation.left().subst(result), equation.right().subst(result), equation.cmp(), DummyEquations.getInstance(), equation.sourceNode())) {
        myErrorReporter.report(new SolveLevelEquationsError(Collections.singletonList(new Pair<>(equation.left(), equation.right())), equation.sourceNode()));
      }
    }

    return result;
  }

  private void calculateUnBased(LevelEquations<InferenceLevelVariable> basedEquations, Set<InferenceLevelVariable> unBased, Map<InferenceLevelVariable, Integer> basedSolution) {
    Set<InferenceLevelVariable> unBasedSet = new HashSet<>();
    if (!myConstantUpperBounds.isEmpty()) {
      for (InferenceLevelVariable var : basedEquations.getVariables()) {
        Level ub = myConstantUpperBounds.get(var);
        if (ub != null) {
          if (ub.getVarPairs().isEmpty()) {
            unBasedSet.add(var);
          } else {
            int sol = basedSolution.get(var);
            if (sol == LevelEquations.INFINITY || ub.getVarPairs().iterator().next().getValue() < sol) {
              unBasedSet.add(var);
            }
          }
        }
      }
    }

    if (!unBasedSet.isEmpty()) {
      Stack<InferenceLevelVariable> stack = new Stack<>();
      for (InferenceLevelVariable var : unBasedSet) {
        stack.push(var);
      }

      boolean ok = true;
      while (!stack.isEmpty()) {
        InferenceLevelVariable var = stack.pop();
        Set<LevelVariable> lowerBounds = myLowerBounds.get(var);
        if (lowerBounds != null) {
          for (LevelVariable lowerBound : lowerBounds) {
            if (lowerBound instanceof InferenceLevelVariable) {
              if (unBasedSet.add((InferenceLevelVariable) lowerBound)) {
                stack.push((InferenceLevelVariable) lowerBound);
              }
            } else if (ok) {
              myErrorReporter.report(new ConstantSolveLevelEquationError(lowerBound, var.getSourceNode()));
              ok = false;
            }
          }
        }
      }
    }

    if (!unBasedSet.isEmpty()) {
      for (Map.Entry<LevelVariable, Set<InferenceLevelVariable>> entry : myUpperBounds.entrySet()) {
        entry.getValue().removeAll(unBasedSet);
      }
    }

    MapDFS<LevelVariable> dfs = new MapDFS<>(myUpperBounds);
    for (LevelVariable var : myUpperBounds.keySet()) {
      if (!(var instanceof InferenceLevelVariable) || ((InferenceLevelVariable) var).isUniverseLike() && !unBasedSet.contains(var)) {
        dfs.visit(var);
      }
    }
    for (InferenceLevelVariable variable : basedEquations.getVariables()) {
      if (unBasedSet.contains(variable) || !variable.isUniverseLike() && !dfs.getVisited().contains(variable)) {
        unBased.add(variable);
      }
    }
  }

  private void reportCycle(List<LevelEquation<InferenceLevelVariable>> cycle, Set<InferenceLevelVariable> unBased) {
    Set<LevelEquation<? extends LevelVariable>> basedCycle = new LinkedHashSet<>();
    for (LevelEquation<InferenceLevelVariable> equation : cycle) {
      if (equation.isInfinity() || equation.getVariable1() != null) {
        basedCycle.add(new LevelEquation<>(equation));
      } else {
        basedCycle.add(new LevelEquation<>(equation.getVariable2() == null || unBased.contains(equation.getVariable2()) ? null : LevelVariable.PVAR, equation.getVariable2(), equation.getConstant()));
      }
    }
    LevelEquation<InferenceLevelVariable> lastEquation = cycle.getLast();
    InferenceLevelVariable var = lastEquation.getVariable1() != null ? lastEquation.getVariable1() : lastEquation.getVariable2();
    myErrorReporter.report(new SolveLevelEquationsError(basedCycle, var.getSourceNode()));
  }
}
