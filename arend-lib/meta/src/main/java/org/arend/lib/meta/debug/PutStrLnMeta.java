package org.arend.lib.meta.debug;

import org.arend.ext.concrete.ConcreteFactory;
import org.arend.ext.concrete.expr.ConcreteExpression;
import org.arend.ext.core.definition.CoreClassField;
import org.arend.ext.core.expr.CoreArrayExpression;
import org.arend.ext.core.expr.CoreClassCallExpression;
import org.arend.ext.core.expr.CoreExpression;
import org.arend.ext.core.expr.CoreIntegerExpression;
import org.arend.ext.core.ops.NormalizationMode;
import org.arend.ext.error.TypecheckingError;
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

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
    // no special casing) and reject anything that isn't a concrete String value.
    ConcreteExpression strArg = contextData.getArguments().getFirst().getExpression();
    TypedExpression result = typechecker.typecheck(strArg, null);
    if (result == null) {
      return null;
    }
    // Normalize the value first so that type synonyms (e.g. `\func Code => String`) and functions
    // returning a refined class unfold to the underlying String-returning term, whose computeType()
    // carries the `bytes` field.
    CoreExpression type = result.getExpression().normalize(NormalizationMode.WHNF).computeType();
    if (!(type.normalize(NormalizationMode.WHNF) instanceof CoreClassCallExpression classCall) || !classCall.getDefinition().getRef().checkName(Names.STRING)) {
      typechecker.getErrorReporter().report(new TypeError(ext.getExpressionPrettifier(), "putStrLn expects a String argument", type, strArg));
      return null;
    }
    String text = decodeString(typechecker, contextData.getFactory(), classCall);
    if (text == null) {
      typechecker.getErrorReporter().report(new TypecheckingError("putStrLn expects a String value with a concrete length and concrete bytes", strArg));
      return null;
    }

    // Everything checks out: print the raw, unquoted, unescaped text.
    ArendConsole console = ext.ui.getConsole(contextData.getMarker());
    console.println(text);

    return typechecker.typecheck(contextData.getArguments().size() > 1 ? contextData.getArguments().get(1).getExpression() : contextData.getFactory().tuple(), contextData.getExpectedType());
  }

  // Materializes the raw bytes of a `String` value and decodes them as UTF-8. Works for any finite,
  // concrete String regardless of how its `bytes` array was built (a literal, a `++`, an indexed
  // `\new Array Byte n f`, or a mix). Returns null if the String is not concrete (some length or byte
  // does not reduce to a literal) or if the bytes are not valid UTF-8; the caller reports an error.
  private @Nullable String decodeString(@NotNull ExpressionTypechecker typechecker, @NotNull ConcreteFactory factory, @NotNull CoreClassCallExpression stringClassCall) {
    // A function returning a fully-implemented class (e.g. String's `++`, returning
    // `\new String {|bytes=>...|}`) has its body folded by the typechecker into a refined result
    // *type* (String {|bytes=>...|}); the call never reduces to a value carrying the field, so the
    // `bytes` implementation is read from the type's class-call, not from the value. We take the
    // closed form so the array can be both read structurally and indexed.
    CoreClassField bytesField = stringClassCall.getDefinition().findField("bytes");
    CoreExpression bytes = bytesField == null ? null : stringClassCall.getClosedImplementation(bytesField);
    if (bytes == null) {
      return null;
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (!collectBytes(typechecker, factory, bytes, out)) {
      return null;
    }

    // Decode strictly: `bytes` may hold arbitrary bytes (e.g. via `\new String { | bytes => ... }`),
    // and a lenient decode would substitute U+FFFD for malformed sequences, prettifying the value as
    // a literal that does not round-trip to the same bytes. Reporting a decode failure is more honest
    // than printing wrong text.
    try {
      return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(out.toByteArray())).toString();
    } catch (CharacterCodingException e) {
      return null;
    }
  }

  // Appends the raw bytes of the array `arrayExpr` to `out`, returning false if it is not fully
  // concrete. A single traversal handles every array shape, so there is no separate fast/slow path:
  //  - an enumerated segment (a literal or the result of `++`) normalizes to a CoreArrayExpression;
  //    its listed elements are read directly, without re-evaluation, keeping a normal String linear;
  //  - a non-enumerated segment (e.g. an indexed `\new Array Byte n f`, which never reduces to a cons
  //    literal) is read by taking its length and evaluating each element by index, `arr i`.
  // A CoreArrayExpression may carry a non-enumerated tail (as in `b :: <indexed>`); the tail is
  // drained by the same routine, so any combination is handled in one pass. The per-index evaluation
  // is an ordinary Java loop, so it does not consume the JVM stack per element.
  private boolean collectBytes(@NotNull ExpressionTypechecker typechecker, @NotNull ConcreteFactory factory, @NotNull CoreExpression arrayExpr, @NotNull ByteArrayOutputStream out) {
    CoreExpression array = arrayExpr.normalize(NormalizationMode.WHNF);
    if (array instanceof CoreArrayExpression consArray) {
      for (CoreExpression element : consArray.getElements()) {
        if (!(element.normalize(NormalizationMode.WHNF) instanceof CoreIntegerExpression intExpr)) return false;
        out.write(intExpr.getBigInteger().intValue() & 0xFF);
      }
      CoreExpression tail = consArray.getTail();
      return tail == null || collectBytes(typechecker, factory, tail, out);
    }

    if (!(array.computeType().normalize(NormalizationMode.WHNF) instanceof CoreClassCallExpression arrayClassCall)) return false;
    CoreClassField lenField = arrayClassCall.getDefinition().findField("len");
    CoreExpression lenImpl = lenField == null ? null : arrayClassCall.getAbsImplementationHere(lenField);
    if (!(lenImpl != null && lenImpl.normalize(NormalizationMode.WHNF) instanceof CoreIntegerExpression lenExpr)) return false;
    ConcreteExpression arrayConcrete = factory.core(array.computeTyped());
    int length = lenExpr.getBigInteger().intValueExact();
    for (int i = 0; i < length; i++) {
      TypedExpression element = typechecker.typecheck(factory.app(arrayConcrete, true, Collections.singletonList(factory.number(i))), null);
      if (element == null || !(element.getExpression().normalize(NormalizationMode.WHNF) instanceof CoreIntegerExpression intExpr)) return false;
      out.write(intExpr.getBigInteger().intValue() & 0xFF);
    }
    return true;
  }
}
