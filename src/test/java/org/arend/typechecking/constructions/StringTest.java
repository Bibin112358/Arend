package org.arend.typechecking.constructions;

import org.arend.core.definition.ClassField;
import org.arend.core.expr.*;
import org.arend.core.expr.visitor.SizeExpressionVisitor;
import org.arend.ext.core.ops.NormalizationMode;
import org.arend.typechecking.TypeCheckingTestCase;
import org.arend.typechecking.error.local.NotEqualExpressionsError;
import org.junit.Before;
import org.junit.Test;

import static org.arend.Matchers.typecheckingError;
import static org.junit.Assert.*;

// String is `Array Byte` (Byte = Fin 256); these tests exercise literal elaboration
// (CheckTypeVisitor.visitStringLiteral) and confirm empirically -- not just by code reading --
// that Fin values built via ConCallExpression.make stay compact (reuse Nat's IntegerExpression
// representation) rather than materializing as chains of nested constructor applications.
public class StringTest extends TypeCheckingTestCase {
  @Before
  public void initializePrelude() {
    typeCheckModule("");
    incModification();
  }

  private static int byteValue(Expression element) {
    IntegerExpression intExpr = element.normalize(NormalizationMode.NF).cast(IntegerExpression.class);
    assertNotNull("expected a compact IntegerExpression, got " + element.normalize(NormalizationMode.NF), intExpr);
    return intExpr.getSmallInteger();
  }

  @Test
  public void literalTypechecks() {
    var result = typeCheckExpr("\"abc\"", null);
    ArrayExpression array = (ArrayExpression) result.expression.normalize(NormalizationMode.NF);
    assertEquals(3, array.getElements().size());
    assertEquals(97, byteValue(array.getElements().get(0)));
    assertEquals(98, byteValue(array.getElements().get(1)));
    assertEquals(99, byteValue(array.getElements().get(2)));
  }

  @Test
  public void highByteValueIsCompact() {
    // '€' (Euro sign) UTF-8 encodes as bytes [226, 130, 172] -- includes a high byte value,
    // exercising the FIN_SUC path many times over in finValue's construction.
    var result = typeCheckExpr("\"€\"", null);
    ArrayExpression array = (ArrayExpression) result.expression.normalize(NormalizationMode.NF);
    assertEquals(3, array.getElements().size());
    assertEquals(226, byteValue(array.getElements().get(0)));
    assertEquals(130, byteValue(array.getElements().get(1)));
    assertEquals(172, byteValue(array.getElements().get(2)));
  }

  @Test
  public void termSizeStaysSmall() {
    // 10,000 repetitions of a high-byte-value character. If Fin values materialized as unary
    // constructor chains, this would produce a term with hundreds of thousands of nodes; if they
    // stay compact (as ConCallExpression.make's special-casing predicts), the size stays
    // proportional to the number of bytes, not their values.
    String literal = "€".repeat(10_000);
    long start = System.nanoTime();
    var result = typeCheckExpr("\"" + literal + "\"", null);
    Expression normalized = result.expression.normalize(NormalizationMode.NF);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    ArrayExpression array = (ArrayExpression) normalized;
    assertEquals(30_000, array.getElements().size());

    int size = SizeExpressionVisitor.getSize(normalized);
    // Generous bound: proportional to the byte count, nowhere near what a unary blowup
    // (sum of byte values, ~7,590,000 for this input) would produce. Measured in practice:
    // term size 30006 (~O(1) overhead per byte) and ~300ms for this input.
    assertTrue("term size " + size + " suggests a unary blowup, not a compact representation", size < 200_000);
    assertTrue("typechecking took " + elapsedMs + "ms, suggesting a performance problem", elapsedMs < 30_000);
  }

  // Regression tests for the original motivating bug: `\data String` had zero constructors, so
  // DefinitionTypechecker inferred Sort.PROP for it, and any record field of type String was
  // silently marked isProperty (proof-irrelevant) -- two records with different string content
  // would incorrectly compare as equal. String := Array Byte has real constructors, so this no
  // longer applies.
  @Test
  public void stringFieldIsNotProperty() {
    typeCheckModule(
        "\\record R (s : String)");
    assertFalse(((ClassField) getDefinition("R.s")).isProperty());
  }

  @Test
  public void stringFieldDistinguishesContent() {
    typeCheckModule(
        "\\record R (s : String)\n" +
        "\\func r1 => \\new R \"abc\"\n" +
        "\\func r2 => \\new R \"abd\"\n" +
        "\\func test : r1 = r2 => idp", 1);
    assertThatErrorsAre(typecheckingError(NotEqualExpressionsError.class));
  }
}
