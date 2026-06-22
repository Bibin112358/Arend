package org.arend.typechecking.levels;

import org.arend.Matchers;
import org.arend.core.context.binding.SortLevelVariable;
import org.arend.core.context.param.DependentLink;
import org.arend.core.context.param.TypedSingleDependentLink;
import org.arend.core.definition.ClassDefinition;
import org.arend.core.definition.ClassField;
import org.arend.core.definition.Definition;
import org.arend.core.definition.FunctionDefinition;
import org.arend.core.expr.ExpressionFactory;
import org.arend.core.expr.PiExpression;
import org.arend.core.expr.ReferenceExpression;
import org.arend.core.expr.UniverseExpression;
import org.arend.core.sort.Sort;
import org.arend.core.sort.SortExpression;
import org.arend.typechecking.TypeCheckingTestCase;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class SortTest extends TypeCheckingTestCase {
  private void checkLevelParameters(String... defNames) {
    for (String defName : defNames) {
      assertEquals("Level check for '" + defName + "' failed", Collections.emptyList(), getDefinition(defName).getLevelParameters());
    }
  }

  private void checkDef(String text) {
    Definition definition = typeCheckDef(text);
    assertEquals(Collections.emptyList(), definition.getLevelParameters());
  }

  @Test
  public void sortTest() {
    typeCheckDef("\\func test => \\Sort", 1);
  }

  @Test
  public void idTest() {
    checkDef("\\func test {A : \\Sort} (a : A) => a");
  }

  @Test
  public void sigmaTest() {
    checkDef("\\func test {A : \\Sort} (B : A -> \\Sort) (p : \\Sigma (x : A) (B x)) => p");
  }

  @Test
  public void piTest() {
    checkDef("\\func test {A : \\Sort} (B : A -> \\Sort) (f : \\Pi (x : A) -> B x) => f");
  }

  @Test
  public void pairTest() {
    typeCheckDef("\\func test (A : \\Sort) => (A,A)", 1);
  }

  @Test
  public void dataTest() {
    typeCheckModule("""
      \\data D (A : \\Sort) (a : A) | con (A -> A)
      \\func test1 => D Nat 7
      \\func test2 => D _ 7
      \\func test3 => D Nat
      \\func test4 (d : D _ 7) => d
      \\func test5 {B : \\Sort} {b : B} (d : D _ b) => d
      """);
    checkLevelParameters("D", "test1", "test2", "test3", "test4", "test5");
    assertEquals(new UniverseExpression(Sort.SET0), ((FunctionDefinition) getDefinition("test1")).getResultType());
    assertEquals(new UniverseExpression(Sort.SET0), ((FunctionDefinition) getDefinition("test2")).getResultType());
    assertEquals(new PiExpression(new TypedSingleDependentLink(true, null, ExpressionFactory.Nat()), new UniverseExpression(Sort.SET0)), ((FunctionDefinition) getDefinition("test3")).getResultType());
  }

  @Test
  public void partiallyAppliedTest() {
    typeCheckModule("""
      \\data D (A : \\Sort) (a : A)
      \\func test => D
      """, 1);
  }

  @Test
  public void functionTest() {
    typeCheckModule("""
      \\data D (A : \\Sort) (a a' : A) | con A
      \\func fun (A : \\Sort) (a : A) => D A a a
      \\func test => fun Nat 7
      """);
    checkLevelParameters("D", "fun", "test");
    assertEquals(new UniverseExpression(Sort.SET0), ((FunctionDefinition) getDefinition("test")).getResultType());
  }

  @Test
  public void functionTest2() {
    typeCheckModule("\\func test (A : \\Sort) (n : Nat) : \\Sort => A");
  }

  @Test
  public void functionTest3() {
    typeCheckModule("""
      \\func test (A : \\Sort) (n : Nat) : \\Sort \\elim n
        | 0 => A
        | suc _ => A
      """, 1);
  }

  @Test
  public void functionTest4() {
    typeCheckModule("""
      \\record R (A : \\Sort) (a a' : A)
      \\func test (A : \\Sort) (a : A) => R A a
      """);
    checkLevelParameters("R", "test");
    FunctionDefinition function = (FunctionDefinition) getDefinition("test");
    assertEquals(new UniverseExpression(new SortExpression.LVar(function.getSortLevelParameters().getFirst())), function.getResultType());
  }

  @Test
  public void recordTest() {
    typeCheckModule("""
      \\record R (A : \\Sort) (a : A)
      \\record S \\extends R
        | B : A -> \\Sort
        | b : B a
      """);
    checkLevelParameters("R", "S");
  }

  @Test
  public void recordTest2() {
    typeCheckModule("""
      \\record R (A : \\Sort) (a a' : A)
      \\func test1 => R Nat 7
      \\func test2 => R _ 7
      \\func test3 => R Nat
      \\func test4 (d : R _ 7) => d
      \\func test5 {B : \\Sort} {b : B} (r : R _ b) => r
      """);
    checkLevelParameters("R", "test1", "test2", "test3", "test4", "test5");
    assertEquals(new UniverseExpression(Sort.SET0), ((FunctionDefinition) getDefinition("test1")).getResultType());
    assertEquals(new UniverseExpression(Sort.SET0), ((FunctionDefinition) getDefinition("test2")).getResultType());
    assertEquals(new UniverseExpression(Sort.SET0), ((FunctionDefinition) getDefinition("test3")).getResultType());
  }

  @Test
  public void recordTest3() {
    typeCheckModule("""
      \\record R (A : \\Sort) (a a' : A)
      \\func test => R
      """, 1);
  }

  @Test
  public void recordTest4() {
    typeCheckModule("""
      \\record R (A B : \\Sort) (a : A) (b : B)
      \\func test => R Nat
      """, 1);
  }

  @Test
  public void recordTest5() {
    typeCheckModule("""
      \\record R (A : \\Sort) (a a' : A)
      \\func test (x : R) => x.a
      """);
  }

  @Test
  public void recordTest6() {
    typeCheckModule("""
      \\record R (A B : \\Sort) (a : A) (b : B)
      \\func test (x : R Nat) => x.b
      """);
  }

  @Test
  public void inferenceTest() {
    typeCheckModule("""
      \\func foo {A : \\Prop} (a a' : A) => 0
      \\func test : 0 = 0 -> Nat => foo idp
      """);
  }

  @Test
  public void truncatedTest() {
    typeCheckModule("""
      \\truncated \\data Trunc (A : \\Sort) : \\Set
        | in A
      \\func test (A : \\3-Type7) : \\Set7 => Trunc A
      """);
    checkLevelParameters("Trunc", "test");
  }

  @Test
  public void truncatedTest2() {
    typeCheckModule("""
      \\truncated \\data Trunc (A : \\Sort) : \\Set
        | in A
      \\func map {A B : \\Sort} (t : Trunc A) (f : A -> B) : Trunc B \\elim t
        | in a => in (f a)
      """);
  }

  @Test
  public void truncatedTest3() {
    typeCheckModule("""
      \\truncated \\data Trunc (A : \\Sort) : \\Prop
        | in A
      \\lemma test {A : \\Sort} (a : A) (n : Nat) : Trunc A \\elim n
        | 0 => in a
        | suc _ => in a
      """);
  }

  @Test
  public void dataTwoVars() {
    typeCheckModule("""
      \\data Or (A B : \\Sort)
        | inl A
        | inr B
      \\func test : \\Set0 => Or Nat \\Set1
      """, 1);
    assertThatErrorsAre(Matchers.typeMismatchError());
  }

  @Test
  public void dataTwoVars2() {
    typeCheckModule("""
      \\data Or (A B : \\Sort)
        | inl A
        | inr B
      \\func test : \\Set0 => Or \\Set1 Nat
      """, 1);
    assertThatErrorsAre(Matchers.typeMismatchError());
  }

  @Test
  public void lamTest() {
    typeCheckDef("""
      \\func test {A : \\Sort} (B : A -> \\Sort) : Nat
        => \\let T => \\lam x => B x \\in 0
      """);
  }

  @Test
  public void splitParameters() {
    FunctionDefinition def = (FunctionDefinition) typeCheckDef("\\func test (A B C : \\Sort) (x : C) (D E : A -> \\Sort) => 0");
    List<SortLevelVariable> vars = def.getSortLevelParameters();
    assertEquals(5, vars.size());
    DependentLink param = def.getParameters();
    assertEquals(new UniverseExpression(new SortExpression.LVar(vars.get(0))), param.getType());
    param = param.getNext();
    assertEquals(new UniverseExpression(new SortExpression.LVar(vars.get(1))), param.getType());
    param = param.getNext();
    assertEquals(new UniverseExpression(new SortExpression.LVar(vars.get(2))), param.getType());
    param = param.getNext().getNext();
    assertEquals(new PiExpression(new TypedSingleDependentLink(true, null, new ReferenceExpression(def.getParameters())), new UniverseExpression(new SortExpression.LVar(vars.get(3)))), param.getType());
    param = param.getNext();
    assertEquals(new PiExpression(new TypedSingleDependentLink(true, null, new ReferenceExpression(def.getParameters())), new UniverseExpression(new SortExpression.LVar(vars.get(4)))), param.getType());
    assertEquals(Arrays.asList(0, 1, 2, 4, 5), def.getSortLevelArgumentIndices());
  }

  @Test
  public void splitFields() {
    ClassDefinition def = (ClassDefinition) typeCheckDef("\\record R (A B C : \\Sort) (x : C) (D E : Nat -> \\Sort)");
    List<SortLevelVariable> vars = def.getSortLevelParameters();
    List<? extends ClassField> fields = def.getPersonalFields();
    assertEquals(5, vars.size());
    assertEquals(new UniverseExpression(new SortExpression.LVar(vars.get(0))), fields.get(0).getResultType());
    assertEquals(new UniverseExpression(new SortExpression.LVar(vars.get(1))), fields.get(1).getResultType());
    assertEquals(new UniverseExpression(new SortExpression.LVar(vars.get(2))), fields.get(2).getResultType());
    assertEquals(new PiExpression(new TypedSingleDependentLink(true, null, ExpressionFactory.Nat()), new UniverseExpression(new SortExpression.LVar(vars.get(3)))), fields.get(4).getResultType());
    assertEquals(new PiExpression(new TypedSingleDependentLink(true, null, ExpressionFactory.Nat()), new UniverseExpression(new SortExpression.LVar(vars.get(4)))), fields.get(5).getResultType());
    assertEquals(Arrays.asList(fields.get(0), fields.get(1), fields.get(2), fields.get(4), fields.get(5)), def.getSortLevelFields());
  }

  @Test
  public void splitFieldsImplement() {
    ClassDefinition def = (ClassDefinition) typeCheckDef("\\record R (A B C : \\Sort) | B => Nat");
    List<SortLevelVariable> vars = def.getSortLevelParameters();
    List<? extends ClassField> fields = def.getPersonalFields();
    assertEquals(2, vars.size());
    assertEquals(new UniverseExpression(new SortExpression.LVar(vars.get(0))), fields.get(0).getResultType());
    assertEquals(new UniverseExpression(new SortExpression.LVar(vars.get(1))), fields.get(2).getResultType());
    assertEquals(Arrays.asList(fields.get(0), fields.get(2)), def.getSortLevelFields());
  }

  @Test
  public void splitFieldsExtend() {
    typeCheckModule("""
      \\record R (A B C D : \\Sort)
      \\record S \\extends R
        | A => Nat
        | C => Nat
      """);
    ClassDefinition def = (ClassDefinition) getDefinition("S");
    List<SortLevelVariable> vars = def.getSortLevelParameters();
    List<? extends ClassField> fields = ((ClassDefinition) getDefinition("R")).getPersonalFields();
    assertEquals(2, vars.size());
    assertEquals(new UniverseExpression(new SortExpression.LVar(vars.get(0))), fields.get(1).getResultType());
    assertEquals(new UniverseExpression(new SortExpression.LVar(vars.get(1))), fields.get(3).getResultType());
    assertEquals(Arrays.asList(fields.get(1), fields.get(3)), def.getSortLevelFields());
  }

  @Test
  public void splitFieldsExtend2() {
    typeCheckModule("""
      \\record R (A B : \\Sort)
      \\record S (C D : \\Sort) (c : C) (d : D)
      \\record T \\extends R, S
      \\func test : \\Type3 => T { | A => \\Set0 | B => \\Set1 | C => \\Set2 | D => \\Set3 }
      """, 1);
    assertThatErrorsAre(Matchers.typeMismatchError());
  }

  @Test
  public void splitFieldsExtend3() {
    typeCheckModule("""
      \\record R (A B : \\Sort)
      \\record S (C D : \\Sort)
      \\record T \\extends R, S
      \\func test (t : T \\Set0 \\Set1 \\Set2 \\Set3) : \\Set3 => t.C
      """, 1);
    assertThatErrorsAre(Matchers.typeMismatchError());
  }

  @Test
  public void splitFieldsExtend4() {
    typeCheckModule("""
      \\record R (A B : \\Sort)
      \\record S (C D : \\Sort)
      \\record T \\extends R, S
      \\func test (t : T \\Set0 \\Set1 \\Set2 \\Set3) : \\Set1 => t.C
      """, 1);
    assertThatErrorsAre(Matchers.typeMismatchError());
  }
}
