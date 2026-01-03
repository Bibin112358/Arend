package org.arend.typechecking.implicitargs.equations;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LevelEquations<Var> {
  private final List<Var> myVariables = new ArrayList<>();
  private final List<LevelEquation<Var>> myEquations = new ArrayList<>();

  public List<LevelEquation<Var>> getEquations() {
    return myEquations;
  }

  public List<Var> getVariables() {
    return myVariables;
  }

  void addVariable(Var var) {
    myVariables.add(var);
  }

  public void add(LevelEquations<Var> equations) {
    myVariables.addAll(equations.myVariables);
    myEquations.addAll(equations.myEquations);
  }

  void addEquation(LevelEquation<Var> equation) {
    myEquations.add(equation);
  }

  public void clear() {
    myVariables.clear();
    myEquations.clear();
  }

  public boolean isEmpty() {
    return myVariables.isEmpty() && myEquations.isEmpty();
  }

  public List<LevelEquation<Var>> solve(Map<Var, BigInteger> solution) {
    Map<Var, List<LevelEquation<Var>>> paths = new HashMap<>();

    solution.put(null, BigInteger.ZERO);
    paths.put(null, new ArrayList<>());
    for (Var var : myVariables) {
      solution.put(var, BigInteger.ZERO);
      paths.put(var, new ArrayList<>());
    }

    for (int i = myVariables.size(); i >= 0; i--) {
      boolean updated = false;
      for (LevelEquation<Var> equation : myEquations) {
        BigInteger a = solution.get(equation.getVariable1());
        BigInteger b = solution.get(equation.getVariable2());
        BigInteger m = equation.getMaxConstant();
        if ((m == null || a.add(m).compareTo(BigInteger.ZERO) < 0) && b.compareTo(a.add(equation.getConstant())) > 0) {
          List<LevelEquation<Var>> newPath = new ArrayList<>(paths.get(equation.getVariable1()));
          newPath.add(equation);
          paths.put(equation.getVariable2(), newPath);
          if (i == 0 || equation.getVariable2() == null) {
            solution.remove(null);
            return paths.get(equation.getVariable2());
          }

          solution.put(equation.getVariable2(), a.add(equation.getConstant()));
          updated = true;
        }
      }
      if (!updated) {
        break;
      }
    }

    solution.remove(null);
    return null;
  }
}
