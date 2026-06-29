package org.arend.core.expr.visitor;

import org.arend.core.context.param.DependentLink;
import org.arend.core.definition.DataDefinition;
import org.arend.core.expr.*;
import org.arend.core.sort.SortExpression;

import java.util.Map;

public class GetInfiniteTypeVisitor extends GetTypeVisitor {
  private final Map<DependentLink, Integer> myParameters;
  private final DataDefinition myThisData;

  public GetInfiniteTypeVisitor(Map<DependentLink, Integer> parameters, DataDefinition thisData) {
    myParameters = parameters;
    myThisData = thisData;
  }

  public GetInfiniteTypeVisitor(Map<DependentLink, Integer> parameters) {
    this(parameters, null);
  }

  @Override
  public Expression visitReference(ReferenceExpression expr, Void params) {
    if (expr.getBinding() instanceof DependentLink param) {
      Integer index = myParameters.get(param);
      if (index != null) {
        return param.getType().replaceInfinityLevel(param);
      }
    }
    return super.visitReference(expr, params);
  }

  @Override
  public Expression visitApp(AppExpression expr, Void params) {
    Expression fun = expr.getFunction();
    while (fun instanceof AppExpression appExpr) {
      fun = appExpr.getFunction();
    }
    if (fun instanceof ReferenceExpression refExpr && refExpr.getBinding() instanceof DependentLink param) {
      Integer index = myParameters.get(param);
      if (index != null) {
        return super.visitApp(expr, params).replaceInfinityLevel(param);
      }
    }

    return super.visitApp(expr, params);
  }

  @Override
  public UniverseExpression visitDataCall(DataCallExpression expr, Void params) {
    return myThisData == null ? super.visitDataCall(expr, params) : new UniverseExpression(new SortExpression.Var(null));
  }
}
