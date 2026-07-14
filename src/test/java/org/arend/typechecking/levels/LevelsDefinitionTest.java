package org.arend.typechecking.levels;

import org.arend.typechecking.TypeCheckingTestCase;
import org.junit.Test;

public class LevelsDefinitionTest extends TypeCheckingTestCase {
  @Test
  public void defTest() {
    typeCheckModule(
      "\\plevels p1,p2\n" +
      "\\func test (A : \\Type p2) : \\Type p1 => A", 1);
  }

  @Test
  public void differentVarsError() {
    typeCheckModule(
      """
        \\plevels p1
        \\plevels p2
        \\func test (A : \\Type p1) (B : \\Type p2) => A
        """, 1);
  }

  @Test
  public void alreadyWithVarsError() {
    typeCheckModule(
      "\\plevels p1,p2\n" +
      "\\func test.{p3} (A : \\Type p2) => A", 1);
  }

  @Test
  public void openTest() {
    typeCheckModule(
      """
        \\module M \\where {
          \\plevels p1,p2
        }
        \\open M
        \\func test (A : \\Type p2) (B : \\Type p1) => A
        """);
  }

  @Test
  public void openTest2() {
    typeCheckModule(
      """
        \\module M \\where {
          \\plevels p1,p2
        }
        \\open M (\\plevel p1, \\plevel p2)
        \\func test (A : \\Type p2) (B : \\Type p1) => A
        """);
  }
}
