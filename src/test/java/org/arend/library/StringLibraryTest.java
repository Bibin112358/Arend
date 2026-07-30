package org.arend.library;

import org.arend.ArendTestCase;
import org.arend.core.definition.Definition;
import org.arend.core.expr.visitor.SizeExpressionVisitor;
import org.arend.error.DummyErrorReporter;
import org.arend.ext.module.ModuleLocation;
import org.arend.ext.module.ModulePath;
import org.arend.frontend.library.CliServerRequester;
import org.arend.frontend.library.FileSourceLibrary;
import org.arend.frontend.library.LibraryManager;
import org.arend.library.classLoader.FileClassLoaderDelegate;
import org.arend.naming.reference.TCDefReferable;
import org.arend.server.ArendServerRequester;
import org.arend.server.ProgressReporter;
import org.arend.server.impl.DefinitionData;
import org.arend.typechecking.computation.UnstoppableCancellationIndicator;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.arend.Matchers.missingClauses;
import static org.arend.Matchers.typecheckingError;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// String literal elaboration lives in arend-lib's extension (the String record, Data.String, and
// the LiteralTypechecker), so these tests load a real arend-lib rather than the bare kernel+Prelude
// that TypeCheckingTestCase provides. Library-level behavior of String itself is covered in
// Data/StringTest.ard.
public class StringLibraryTest extends ArendTestCase {
  // Snippet sources go into a rule-managed folder so they are deleted after each test.
  @Rule
  public final TemporaryFolder tempFolder = new TemporaryFolder();

  private Path repoRoot;
  private LibraryManager libraryManager;

  @Override
  protected ArendServerRequester getRequester() {
    libraryManager = new LibraryManager(DummyErrorReporter.INSTANCE);
    return new CliServerRequester(libraryManager);
  }

  @Before
  public void loadArendLib() {
    repoRoot = findRepoRoot();
    Path configFile = repoRoot.resolve("arend-lib").resolve("arend.yaml");
    FileSourceLibrary arendLib = FileSourceLibrary.fromConfigFile(configFile, false, DummyErrorReporter.INSTANCE);
    assertNotNull("Could not load arend-lib from " + configFile, arendLib);
    libraryManager.updateLibrary(arendLib, server);

    // No Prelude "warmup" here: each test gets a fresh server (ArendTestCase's @Before), so its
    // typecheckSnippet call is the first module typechecked in the session, and that module uses
    // string literals. This keeps the tests a regression guard that string-literal elaboration works
    // "cold", before any other module is checked. Do not add a warmup.
  }

  private static Path findRepoRoot() {
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null) {
      if (Files.isRegularFile(dir.resolve("arend-lib").resolve("arend.yaml"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("Could not locate the repo root containing arend-lib from " + Paths.get("").toAbsolutePath());
  }

  // Typechecks `source` as a standalone module depending on arend-lib (so it can \import
  // Data.String and use string literals), reusing arend-lib's own compiled extension classes.
  private ModuleLocation typecheckSnippet(String libraryName, String source) throws IOException {
    Path srcDir = tempFolder.newFolder("arend-string-test-" + libraryName).toPath();
    Files.writeString(srcDir.resolve("Snippet.ard"), source);

    // args: name, isExternal, modStamp, dependencies, version, langVersion, extensionMainClass,
    // modules, sourceBasePath, binaryBasePath, testBasePath, classLoaderDelegate
    FileSourceLibrary snippetLib = new FileSourceLibrary(libraryName, false, -1,
        List.of("arend-lib"), null, null, "org.arend.lib.StdExtension", null,
        srcDir, null, null, new FileClassLoaderDelegate(repoRoot.resolve("arend-lib").resolve("ext")));
    libraryManager.updateLibrary(snippetLib, server);

    ModuleLocation module = server.findModule(new ModulePath("Snippet"), libraryName, false, true);
    assertNotNull("Snippet module was not found in " + libraryName, module);
    server.getCheckerFor(Collections.singletonList(module)).typecheck(UnstoppableCancellationIndicator.INSTANCE, ProgressReporter.empty());
    return module;
  }

  // Soundness guard: a string literal inhabits String without going through a constructor, so
  // `\case "s" \with {}` must not be accepted as exhaustive -- otherwise one could "prove" Empty.
  // String is a \record, which the coverage checker sees as a single implicit shape (regardless of
  // its fields), so exactly one clause is missing.
  @Test
  public void stringLiteralCaseIsNotExhaustive() throws IOException {
    typecheckSnippet("string-coverage-probe",
        "\\import Data.String\n" +
        "\\data Empty\n" +
        "\\func f : Empty => \\case \"s\" \\with {}\n");
    assertThatErrorsAre(missingClauses(1));
  }

  // If Fin values (String's byte elements) materialized as unary constructor chains instead of
  // staying compact, a 10,000-character literal would produce a term with hundreds of thousands of
  // nodes. The bound is generous -- this is a regression guard against a unary blowup in the term
  // representation.
  @Test
  public void termSizeStaysSmall() throws IOException {
    String literal = "€".repeat(10_000); // '€' UTF-8-encodes to 3 bytes, for 30,000 bytes total
    ModuleLocation module = typecheckSnippet("string-perf-probe",
        "\\import Data.String\n" +
        "\\func bigString => \"" + literal + "\"\n");
    assertTrue("expected no errors", getAllErrors().isEmpty());

    Collection<? extends DefinitionData> definitions = server.getResolvedDefinitions(module);
    Definition bigString = definitions.stream()
        .filter(d -> d.definition().getData() instanceof TCDefReferable ref && ref.getRefName().equals("bigString"))
        .map(d -> ((TCDefReferable) d.definition().getData()).getTypechecked())
        .filter(java.util.Objects::nonNull)
        .findAny().orElse(null);
    assertNotNull("bigString did not typecheck", bigString);

    int size = SizeExpressionVisitor.getSize(bigString);
    assertTrue("term size " + size + " suggests a unary blowup, not a compact representation", size < 200_000);
  }

  // putStrLn prints a String literal as raw text; the snippet must typecheck without errors.
  // (The raw output goes to stdout via the extension's console; here we only assert it elaborates.)
  @Test
  public void putStrLnAcceptsStringLiteral() throws IOException {
    typecheckSnippet("putstrln-literal",
        "\\import Debug.Meta\n" +
        "\\import Data.String\n" +
        "\\func test => putStrLn \"h\u00e9llo\"\n");
    assertTrue("expected no errors", getAllErrors().isEmpty());
  }

  // The continuation of the inner call is its result, so the outer call receives "b" as a String.
  // The inner argument must not be elaborated again: that would print "a" a second time.
  @Test
  public void putStrLnReturnsContinuation() throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    try {
      System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
      typecheckSnippet("putstrln-continuation",
          "\\import Debug.Meta\n" +
          "\\import Data.String\n" +
          "\\func test => putStrLn (putStrLn \"a\" \"b\")\n");
    } finally {
      System.setOut(originalOut);
    }
    assertTrue("expected no errors", getAllErrors().isEmpty());
    assertEquals("a" + System.lineSeparator() + "b" + System.lineSeparator(), output.toString(StandardCharsets.UTF_8));
  }

  // An indexed String value (`\new String (\new Array Byte n f)`) does not reduce to an enumerated
  // byte literal, yet it is a perfectly valid, concrete String. putStrLn must still print it: it
  // materializes the bytes by reading the length and evaluating each element by index. Here the
  // bytes come from "AB" via an index function, so the raw output is "AB".
  @Test
  public void putStrLnAcceptsIndexedArrayString() throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    try {
      System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
      typecheckSnippet("putstrln-indexed",
          "\\import Debug.Meta\n" +
          "\\import Data.String\n" +
          "\\func src : String => \"AB\"\n" +
          "\\func test => putStrLn (\\new String (\\new Array Byte src.bytes.len (\\lam i => src.bytes i)))\n");
    } finally {
      System.setOut(originalOut);
    }
    assertTrue("expected no errors", getAllErrors().isEmpty());
    assertEquals("AB" + System.lineSeparator(), output.toString(StandardCharsets.UTF_8));
  }

  // A non-literal String value (built with ++) still decodes to a literal, so putStrLn accepts it.
  @Test
  public void putStrLnAcceptsDecodableConcatenation() throws IOException {
    typecheckSnippet("putstrln-concat",
        "\\import Debug.Meta\n" +
        "\\import Data.String\n" +
        "\\func test => putStrLn (\"foo\" ++ \"bar\")\n");
    assertTrue("expected no errors", getAllErrors().isEmpty());
  }

  // A non-String argument is rejected: putStrLn reports a type error and the definition fails.
  @Test
  public void putStrLnRejectsNonString() throws IOException {
    typecheckSnippet("putstrln-non-string",
        "\\import Debug.Meta\n" +
        "\\import Data.String\n" +
        "\\func test => putStrLn 42\n");
    assertThatErrorsAre(typecheckingError());
  }

  // A String value that cannot be reduced to a concrete literal (an opaque parameter) is rejected.
  @Test
  public void putStrLnRejectsOpaqueString() throws IOException {
    typecheckSnippet("putstrln-opaque",
        "\\import Debug.Meta\n" +
        "\\import Data.String\n" +
        "\\func test (x : String) => putStrLn x\n");
    assertThatErrorsAre(typecheckingError());
  }

  // Type synonym: a value whose declared type is a \func alias of String must still print.
  @Test
  public void putStrLnAcceptsTypeSynonym() throws IOException {
    typecheckSnippet("putstrln-synonym",
        "\\import Debug.Meta\n" +
        "\\import Data.String\n" +
        "\\func Code : \\Type => String\n" +
        "\\func rep (n : Nat) : String \\elim n\n" +
        "  | 0 => \"x\"\n" +
        "  | suc n => \"y\" ++ rep n\n" +
        "\\func mk (n : Nat) : Code => rep n\n" +
        "\\func test => putStrLn (mk 3)\n");
    assertTrue("expected no errors", getAllErrors().isEmpty());
  }

  // Regression guard for the PrintMeta revert: println still elaborates a String argument without errors.
  @Test
  public void printlnStillAcceptsString() throws IOException {
    typecheckSnippet("println-string",
        "\\import Debug.Meta\n" +
        "\\import Data.String\n" +
        "\\func test => println (\"Hello\" ++ \" \" ++ \"World\" ++ \"!\")\n");
    assertTrue("expected no errors", getAllErrors().isEmpty());
  }
}
