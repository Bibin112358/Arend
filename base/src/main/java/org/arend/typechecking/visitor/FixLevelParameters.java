package org.arend.typechecking.visitor;

import org.arend.core.context.binding.LevelVariable;
import org.arend.core.definition.*;
import org.arend.core.expr.*;
import org.arend.core.expr.visitor.VoidExpressionVisitor;
import org.arend.core.sort.Level;
import org.arend.core.sort.Sort;
import org.arend.core.sort.SortExpression;
import org.arend.core.subst.SingleLevel;
import org.arend.core.subst.Levels;
import org.arend.core.subst.ListLevels;
import org.arend.ext.core.ops.CMP;
import org.arend.ext.util.Pair;
import org.arend.typechecking.implicitargs.equations.DummyEquations;

import java.util.*;

// TODO[sorts]: Delete this
public class FixLevelParameters extends VoidExpressionVisitor<Void> {
  private final Set<? extends Definition> myDefinitions;
  private final boolean myRemovePLevels;
  private final boolean myRemoveHLevels;

  private FixLevelParameters(Set<? extends Definition> definitions, boolean removePLevels, boolean removeHLevels) {
    myDefinitions = definitions;
    myRemovePLevels = removePLevels;
    myRemoveHLevels = removeHLevels;
  }

  public static void fix(Set<? extends TopLevelDefinition> definitions, Set<Definition> newDefs) {
    for (Definition definition : definitions) {
      if (definition.hasNonTrivialPLevelParameters()) return;
    }

    Set<Definition> extendedDefs = new HashSet<>();
    for (Definition definition : definitions) {
      if (definition instanceof DataDefinition) {
        boolean found = false;
        for (Constructor constructor : ((DataDefinition) definition).getConstructors()) {
          if (constructor.getBody() != null) {
            found = true;
            break;
          }
        }
        if (found) {
          extendedDefs.addAll(((DataDefinition) definition).getConstructors());
        }
      } else if (definition instanceof ClassDefinition) {
        extendedDefs.addAll(((ClassDefinition) definition).getPersonalFields());
      }
    }
    extendedDefs.addAll(definitions);

    FindLevelParameters visitor = new FindLevelParameters(extendedDefs);
    for (Definition definition : definitions) {
      if (definition.hasNonTrivialPLevelParameters()) {
        visitor.hasPLevels = true;
      }
    }
    if (visitor.hasPLevels) return;
    for (Definition definition : definitions) {
      if (definition.accept(visitor, null)) break;
    }
    if (visitor.hasPLevels) return;

    for (TopLevelDefinition definition : definitions) {
      if (newDefs.contains(definition)) {
        definition.setLevelParameters(Collections.emptyList());
      }
    }

    FixLevelParameters fixer = new FixLevelParameters(extendedDefs, !visitor.hasPLevels, true);
    for (Definition definition : definitions) {
      if (newDefs.contains(definition)) definition.accept(fixer, null);
    }
  }

  public static void fix(Expression expr) {
    expr.accept(new FixLevelParameters(null, false, false), null);
  }

  private static void removeLevels(LeveledDefCallExpression defCall, boolean removePLevels, boolean removeHLevels) {
    Levels levels;
    if (removePLevels && removeHLevels) {
      levels = Levels.EMPTY;
    } else {
      List<Level> list;
      List<? extends Level> oldList = defCall.getLevels().toList();
      if (removePLevels) {
        list = new ArrayList<>(oldList.subList(oldList.size() - defCall.getDefinition().getLevelParameters().size(), oldList.size()));
      } else {
        list = new ArrayList<>(oldList.subList(0, defCall.getDefinition().getLevelParameters().size()));
      }
      levels = new ListLevels(list);
    }
    defCall.setLevels(levels);
  }

  private void processDefCall(DefCallExpression defCall) {
    if (!(defCall instanceof LeveledDefCallExpression leveled)) return;
    if (myDefinitions == null) {
      List<? extends LevelVariable> params = leveled.getDefinition().getLevelParameters();
      if (params != null && (leveled.getLevels() instanceof SingleLevel || leveled.getLevels().toList().size() != params.size())) {
        removeLevels(leveled, params.isEmpty(), true);
      }
    } else if (myDefinitions.contains(leveled.getDefinition())) {
      removeLevels(leveled, myRemovePLevels, myRemoveHLevels);
    }
  }

  @Override
  public Void visitDefCall(DefCallExpression expr, Void params) {
    processDefCall(expr);
    return super.visitDefCall(expr, params);
  }

  @Override
  protected void processConCall(ConCallExpression expr, Void params) {
    processDefCall(expr);
  }

  private Level removeVars(Level level) {
    if (level.isClosed()) return level;
    int result = level.getConstant();
    for (Map.Entry<LevelVariable, Integer> entry : level.getVarPairs()) {
      if (entry.getValue() > result) result = entry.getValue();
    }
    return new Level(result);
  }

  private Sort removeVars(Sort sort) {
    return new Sort(myRemovePLevels ? removeVars(sort.getPLevel()) : sort.getPLevel(), myRemoveHLevels ? removeVars(sort.getHLevel()) : sort.getHLevel());
  }

  private SortExpression removeVars(SortExpression sort) {
    return switch (sort) {
      case SortExpression.Const(Sort aSort) -> new SortExpression.Const(removeVars(aSort));
      case SortExpression.Max max -> {
        List<SortExpression> sorts = new ArrayList<>(max.getSorts().size());
        for (SortExpression aSort : max.getSorts()) {
          sorts.add(removeVars(aSort));
        }
        yield SortExpression.makeMax(sorts);
      }
      case SortExpression.Prev prev -> SortExpression.makePrev(removeVars(prev.getSort()));
      case SortExpression.Succ succ -> SortExpression.makeSucc(removeVars(succ.getSort()));
      case SortExpression.Pi pi -> SortExpression.makePi(removeVars(pi.getDomain()), removeVars(pi.getCodomain()));
      case SortExpression.Var var -> var;
      case SortExpression.Field field -> field;
      case SortExpression.InfVar var -> var;
    };
  }

  private SingleLevel removeVars(SingleLevel levels) {
    return new SingleLevel(myRemovePLevels ? removeVars(levels.getLevel()) : levels.getLevel());
  }

  @Override
  public Void visitTypeConstructor(TypeConstructorExpression expr, Void params) {
    if (expr.getLevels() instanceof SingleLevel) {
      expr.setLevels(removeVars((SingleLevel) expr.getLevels()));
    } else {
      List<Level> list = new ArrayList<>();
      List<? extends LevelVariable> levelParameters = expr.getDefinition().getLevelParameters();
      List<? extends Level> oldList = expr.getLevels().toList();
      for (int i = 0; i < levelParameters.size(); i++) {
        if (myRemovePLevels) {
          list.add(removeVars(oldList.get(i)));
        } else {
          list.add(oldList.get(i));
        }
      }
      expr.setLevels(new ListLevels(list));
    }
    return super.visitTypeConstructor(expr, params);
  }

  @Override
  public Void visitData(DataDefinition def, Void params) {
    def.setSortExpression(removeVars(def.getSortExpression()));
    return super.visitData(def, params);
  }

  @Override
  public Void visitClass(ClassDefinition def, Void params) {
    Levels idLevels = def.makeIdLevels();
    def.getSuperLevels().entrySet().removeIf(entry -> entry.getValue().compare(idLevels, CMP.EQ, DummyEquations.getInstance(), null));
    for (Map.Entry<ClassField, AbsExpression> entry : def.getImplemented()) {
      if (entry.getValue().getBinding() != null) {
        entry.getValue().getBinding().getType().accept(this, null);
      }
    }
    for (Map.Entry<ClassField, Pair<AbsExpression, Boolean>> entry : def.getDefaults()) {
      if (entry.getValue().proj1.getBinding() != null) {
        entry.getValue().proj1.getBinding().getType().accept(this, null);
      }
    }
    return super.visitClass(def, params);
  }
}
