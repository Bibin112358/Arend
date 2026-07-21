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
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.arend.Matchers.missingClauses;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// String only elaborates literal syntax (and needs Prelude's own nil/:: refs) via arend-lib's
// extension now, so testing it needs a real arend-lib load, not just the bare kernel+Prelude that
// TypeCheckingTestCase provides. These mirror what used to live in the now-deleted base-level
// StringTest.java and two of CoverageTest.java's String cases, before String moved out of the
// kernel into arend-lib (see Data/String.ard, Data/StringTest.ard for the library-level coverage).
public class StringLibraryTest extends ArendTestCase {
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

    // Deliberately no Prelude "warmup" here: each test gets a fresh server (ArendTestCase's
    // @Before), so its typecheckSnippet call is the *first* module typechecked in the session --
    // and it uses string literals. That first-module-uses-a-literal path is exactly what used to
    // fail with "Cannot check string" when literal elaboration needed an already-typechecked
    // Prelude at resolve time. Since checkByteArray moved that work to typecheck time (String's
    // resolveString now only resolves names), the path is safe, and running these tests without a
    // warmup keeps them as a real regression guard for it. Do not add a warmup back.
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
    Path srcDir = Files.createTempDirectory("arend-string-test-" + libraryName);
    Files.writeString(srcDir.resolve("Snippet.ard"), source);

    FileSourceLibrary snippetLib = new FileSourceLibrary(libraryName, false, -1,
        List.of("arend-lib"), null, "org.arend.lib.StdExtension", null,
        srcDir, null, null, new FileClassLoaderDelegate(repoRoot.resolve("arend-lib").resolve("ext")));
    libraryManager.updateLibrary(snippetLib, server);

    ModuleLocation module = server.findModule(new ModulePath("Snippet"), libraryName, false, true);
    assertNotNull("Snippet module was not found in " + libraryName, module);
    server.getCheckerFor(Collections.singletonList(module)).typecheck(UnstoppableCancellationIndicator.INSTANCE, ProgressReporter.empty());
    return module;
  }

  // Regression test for a soundness hole: `String` used to be declared with zero constructors
  // (`\data String`), so the coverage checker treated it as uninhabited even though string
  // literals (bypassing the constructor mechanism) actually inhabit it -- `\case "s" \with {}`
  // could "prove" `Empty`. This is the exact snippet that originally motivated that fix; it must
  // stay rejected regardless of how String is represented underneath.
  //
  // Expects 1 missing clause, not 2: in the original v1 fix (String := Array Byte, a transparent
  // alias), the coverage checker saw through to Array's own two-constructor shape (nil/::). Now
  // that String is a \record wrapping `bytes`, a record always has exactly one implicit shape
  // from the coverage checker's perspective, regardless of what's inside its fields.
  @Test
  public void stringLiteralCaseIsNotExhaustive() throws IOException {
    typecheckSnippet("string-coverage-probe",
        "\\import Data.String\n" +
        "\\data Empty\n" +
        "\\func f : Empty => \\case \"s\" \\with {}\n");
    assertThatErrorsAre(missingClauses(1));
  }

  // If Fin values (String's byte elements) materialized as unary constructor chains instead of
  // staying compact, a 10,000-character literal would produce a term with hundreds of thousands
  // of nodes. Bounds are generous -- this is a regression guard against a unary blowup, not a
  // tight performance benchmark.
  @Test
  public void termSizeStaysSmall() throws IOException {
    String literal = "€".repeat(10_000); // '€' UTF-8-encodes to 3 bytes, for 30,000 bytes total
    long start = System.nanoTime();
    ModuleLocation module = typecheckSnippet("string-perf-probe",
        "\\import Data.String\n" +
        "\\func bigString => \"" + literal + "\"\n");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
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
    assertTrue("typechecking took " + elapsedMs + "ms, suggesting a performance problem", elapsedMs < 30_000);
  }
}
