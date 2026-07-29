package org.arend.lib.meta.debug;

import org.arend.ext.concrete.expr.ConcreteExpression;
import org.arend.ext.concrete.expr.ConcreteStringExpression;
import org.arend.ext.core.expr.CoreClassCallExpression;
import org.arend.ext.core.expr.CoreExpression;
import org.arend.ext.core.ops.NormalizationMode;
import org.arend.ext.error.TypecheckingError;
import org.arend.ext.prettifier.ExpressionPrettifier;
import org.arend.ext.prettifier.MergingExpressionPrettifier;
import org.arend.ext.typechecking.BaseMetaDefinition;
import org.arend.ext.typechecking.ContextData;
import org.arend.ext.typechecking.ExpressionTypechecker;
import org.arend.ext.typechecking.TypedExpression;
import org.arend.ext.ui.ArendConsole;
import org.arend.lib.StdExtension;
import org.arend.lib.error.TypeError;
import org.arend.lib.util.Names;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.stream.Stream;

// Prints a String value as raw, unquoted, unescaped text (with a trailing newline).
// In deliberate contrast to `println` (PrintMeta), which renders values quoted/escaped and
// round-trippable, `putStrLn` writes the raw characters of a String verbatim.
//
// `putStrLn s k` prints `s` and elaborates to the optional continuation `k`; with no continuation,
// `putStrLn s` elaborates to Unit.
public class PutStrLnMeta extends BaseMetaDefinition {
  private final StdExtension ext;

  public PutStrLnMeta(StdExtension ext) {
    this.ext = ext;
  }

  @Override
  public boolean @Nullable [] argumentExplicitness() {
    return new boolean[] { true, true };
  }

  @Override
  public int numberOfOptionalExplicitArguments() {
    return 1;
  }

  @Override
  public boolean allowExcessiveArguments() {
    return false;
  }

  @Override
  public @Nullable TypedExpression invokeMeta(@NotNull ExpressionTypechecker typechecker, @NotNull ContextData contextData) {
    // Typecheck the argument (a String literal also elaborates to a String value here, so it needs
    // no special casing) and reject anything that isn't a String value reducing to a literal.
    ConcreteExpression strArg = contextData.getArguments().getFirst().getExpression();
    TypedExpression result = typechecker.typecheck(strArg, null);
    if (result == null) {
      return null;
    }
    // The String value is decoded back to a literal by prettifying it. The typechecker's prettifier is
    // context-aware, but it only carries the extensions of the library currently being typechecked, so
    // it cannot decode a String when `putStrLn` is invoked from a library that merely depends on
    // arend-lib. Merging it with arend-lib's own prettifier (which handles `\new String { | bytes => ... }`)
    // keeps the context-aware prettifier's precedence while guaranteeing that Strings always decode.
    ExpressionPrettifier prettifier = new MergingExpressionPrettifier(
      Stream.of(typechecker.getExpressionPrettifier(), ext.getExpressionPrettifier()).filter(Objects::nonNull).toList());
    CoreExpression type = result.getExpression().computeType();
    if (!(type.normalize(NormalizationMode.WHNF) instanceof CoreClassCallExpression classCall) || !classCall.getDefinition().getRef().checkName(Names.STRING)) {
      typechecker.getErrorReporter().report(new TypeError(prettifier, "putStrLn expects a String argument", type, strArg));
      return null;
    }
    ConcreteExpression pretty = prettifier.prettify(result.getExpression(), (e, d) -> null);
    if (!(pretty instanceof ConcreteStringExpression stringExpr)) {
      typechecker.getErrorReporter().report(new TypecheckingError("putStrLn expects a String value that reduces to a literal", strArg));
      return null;
    }

    // Everything checks out: print the raw, unquoted, unescaped text.
    ArendConsole console = ext.ui.getConsole(contextData.getMarker());
    console.println(stringExpr.getUnescapedString());

    return typechecker.typecheck(contextData.getArguments().size() > 1 ? contextData.getArguments().get(1).getExpression() : contextData.getFactory().tuple(), contextData.getExpectedType());
  }
}
