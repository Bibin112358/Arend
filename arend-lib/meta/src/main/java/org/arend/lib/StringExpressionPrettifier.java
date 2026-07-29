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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

// Prettifies String values back into string literals. String is a library-defined record (not a
// Prelude type with a cached kernel ref), so it is identified by name/module via ArendRef.checkName.
public class StringExpressionPrettifier implements ExpressionPrettifier {
  private final StdExtension ext;

  public StringExpressionPrettifier(StdExtension ext) {
    this.ext = ext;
  }

  @Override
  public @Nullable ConcreteExpression prettify(@NotNull CoreExpression expression, @NotNull ExpressionPrettifier defaultPrettifier) {
    // A function returning a fully-implemented class (e.g. String's `++`, returning
    // `\new String {|bytes=>...|}`) has its body folded by the typechecker into a refined result
    // *type* (String {|bytes=>...|}); the call never reduces to a CoreNewExpression, so the `bytes`
    // implementation lives in the type, not the value. Hence we read the type, not the value.
    // We normalize the value first so that type synonyms (e.g. `\func Code => String`) unfold to
    // the underlying String-returning term, whose computeType() still carries the `bytes` field.
    if (!(expression.normalize(NormalizationMode.WHNF).computeType().normalize(NormalizationMode.WHNF) instanceof CoreClassCallExpression classCall) || !classCall.getDefinition().getRef().checkName(Names.STRING)) {
      return null;
    }

    CoreClassField bytesField = classCall.getDefinition().findField("bytes");
    CoreExpression bytesValue = bytesField == null ? null : classCall.getAbsImplementationHere(bytesField);
    if (!(bytesValue != null && bytesValue.normalize(NormalizationMode.WHNF) instanceof CoreArrayExpression array) || array.getTail() != null) {
      return null;
    }

    ByteArrayOutputStream bytes = new ByteArrayOutputStream(array.getElements().size());
    for (CoreExpression element : array.getElements()) {
      if (!(element.normalize(NormalizationMode.WHNF) instanceof CoreIntegerExpression intExpr)) return null;
      bytes.write(intExpr.getBigInteger().intValue() & 0xFF);
    }
    // Decode strictly: `bytes` may hold arbitrary bytes (e.g. via `\new String { | bytes => ... }`),
    // and a lenient decode would substitute U+FFFD for malformed sequences, prettifying the value
    // as a literal that does not round-trip to the same bytes. Falling back to the default record
    // display (by returning null) is more honest than showing a wrong literal.
    try {
      return ext.makeString(StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes.toByteArray())).toString());
    } catch (CharacterCodingException e) {
      return null;
    }
  }
}
