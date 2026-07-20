package org.arend.lib;

import org.arend.ext.concrete.expr.ConcreteExpression;
import org.arend.ext.core.definition.CoreClassField;
import org.arend.ext.core.expr.CoreArrayExpression;
import org.arend.ext.core.expr.CoreClassCallExpression;
import org.arend.ext.core.expr.CoreExpression;
import org.arend.ext.core.expr.CoreIntegerExpression;
import org.arend.ext.core.ops.NormalizationMode;
import org.arend.ext.prettifier.ExpressionPrettifier;
import org.arend.lib.util.Names;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

// Mirrors the Prelude.DEP_ARRAY identity-check precedent in ToAbstractVisitor for printing array
// literals, but for a library-defined (non-Prelude) record: since there's no cached Java field to
// compare against by identity, String is identified by name/module via ArendRef.checkName, the same
// mechanism StdLiteralTypechecker already uses to resolve String/bytes for literal elaboration.
public class StringExpressionPrettifier implements ExpressionPrettifier {
  private final StdExtension ext;

  public StringExpressionPrettifier(StdExtension ext) {
    this.ext = ext;
  }

  @Override
  public @Nullable ConcreteExpression prettify(@NotNull CoreExpression expression, @NotNull ExpressionPrettifier defaultPrettifier) {
    // A function whose result type is a class with all fields already implemented (e.g. String's
    // `++`, which returns `\new String {|bytes=>...|}`) gets its body optimized away by the
    // typechecker in favor of a refined result type (String {|bytes=>...|}) that already carries
    // the same field implementation -- see DefinitionTypechecker's reallyHideBody. So a call to such
    // a function never reduces to a CoreNewExpression under normalize(); its *type*, not its value,
    // is where the `bytes` implementation lives. computeType() works uniformly for both a literal
    // (whose precise type is the same singleton-refined form) and a call to such a function.
    if (!(expression.computeType().normalize(NormalizationMode.WHNF) instanceof CoreClassCallExpression classCall) || !classCall.getDefinition().getRef().checkName(Names.STRING)) {
      return null;
    }

    CoreClassField bytesField = classCall.getDefinition().findField("bytes");
    CoreExpression bytesValue = bytesField == null ? null : classCall.getAbsImplementationHere(bytesField);
    if (!(bytesValue != null && bytesValue.normalize(NormalizationMode.NF) instanceof CoreArrayExpression array) || array.getTail() != null) {
      return null;
    }

    ByteArrayOutputStream bytes = new ByteArrayOutputStream(array.getElements().size());
    for (CoreExpression element : array.getElements()) {
      if (!(element.normalize(NormalizationMode.NF) instanceof CoreIntegerExpression intExpr)) return null;
      bytes.write(intExpr.getBigInteger().intValue() & 0xFF);
    }
    return ext.getFactory().string(bytes.toString(StandardCharsets.UTF_8));
  }
}
