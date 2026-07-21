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

    // Typecheck some other, unrelated arend-lib module first, purely to force Prelude to be fully
    // typechecked (not just resolved) before any snippet below runs -- otherwise the very first
    // module ever typechecked in this server session, if it's the one using string literals, can
    // see an uninitialized Prelude and fail with "Cannot check string" regardless of imports.
    ModuleLocation warmup = server.findModule(new ModulePath("Logic"), "arend-lib", false, true);
    assertNotNull("Could not find arend-lib's Logic module", warmup);
    server.getCheckerFor(Collections.singletonList(warmup)).typecheck(UnstoppableCancellationIndicator.INSTANCE, ProgressReporter.empty());
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

  // BENCHMARK (not a pass/fail regression): models the AST-printing use case -- build a string by
  // repeated concatenation and measure the time to normalize the result to NF, isolating String's
  // `++`. Three patterns: (a) prepend many small tokens (token ++ acc), (b) append many small tokens
  // (acc ++ token -- the natural left-to-right printing order), (c) wrap with a few large strings
  // (token ++ acc ++ token). Runs the (N-deep) normalization on a large-stack thread. Prints a table.
  @Test
  public void benchmarkStringConcat() throws IOException {
    StringBuilder t = new StringBuilder();
    t.append("buildMs = normalize the concatenation; readMs = normalize toList of the bytes (full materialization, ~ what printing costs)\n");
    t.append(String.format("%-20s %8s %8s | %8s %8s %10s%n", "pattern", "N", "totBytes", "buildMs", "readMs", "readSize"));
    for (int n : new int[]{500, 1000, 2000}) {
      t.append(benchConcatRow("(a) prepend-small", "prependN " + n + " \"\" tok", 8, n));
      t.append(benchConcatRow("(b) append-small",  "appendN "  + n + " \"\" tok", 8, n));
    }
    for (int m : new int[]{5, 10, 20}) {
      t.append(benchConcatRow("(c) wrap-large",    "wrapN "    + m + " tok tok", 2000, m));
    }
    Path out = Paths.get(System.getenv().getOrDefault("CLAUDE_JOB_DIR", "/tmp") + "/tmp/concat-bench.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, t.toString());
    System.out.println("\n===== STRING CONCAT BENCHMARK =====\n" + t + "===================================\n");
  }

  private String benchConcatRow(String label, String resultExpr, int tokLen, int n) {
    String token = "a".repeat(tokLen);
    String src =
        "\\import Data.String\n" +
        "\\import Data.Array (toList)\n" +
        "\\func appendN (k : Nat) (acc t : String) : String \\elim k | 0 => acc | suc k => appendN k (acc ++ t) t\n" +
        "\\func prependN (k : Nat) (acc t : String) : String \\elim k | 0 => acc | suc k => prependN k (t ++ acc) t\n" +
        "\\func wrapN (k : Nat) (acc t : String) : String \\elim k | 0 => acc | suc k => wrapN k (t ++ acc ++ t) t\n" +
        "\\func tok => \"" + token + "\"\n" +
        "\\func result => " + resultExpr + "\n" +
        "\\func materialized => toList result.bytes\n";
    try {
      ModuleLocation module = typecheckSnippet("bench-concat-" + label.replaceAll("[^a-zA-Z]", "") + "-" + n, src);
      org.arend.core.expr.Expression buildBody = bodyOf(module, "result");
      org.arend.core.expr.Expression readBody = bodyOf(module, "materialized");
      if (buildBody == null || readBody == null) {
        return String.format("%-20s %8d %8d | %8s %8s %10s%n", label, n, tokLen * n, "NOBODY", "-", "-");
      }
      long[] buildMs = new long[1], readMs = new long[1];
      int[] readSize = new int[1];
      runOnBigStack(() -> {
        long s1 = System.nanoTime();
        buildBody.normalize(org.arend.ext.core.ops.NormalizationMode.NF);
        buildMs[0] = (System.nanoTime() - s1) / 1_000_000;
        long s2 = System.nanoTime();
        org.arend.core.expr.Expression nf = readBody.normalize(org.arend.ext.core.ops.NormalizationMode.NF);
        readMs[0] = (System.nanoTime() - s2) / 1_000_000;
        readSize[0] = SizeExpressionVisitor.getSize(nf);
      });
      return String.format("%-20s %8d %8d | %8d %8d %10d%n", label, n, tokLen * n, buildMs[0], readMs[0], readSize[0]);
    } catch (Throwable e) {
      return String.format("%-20s %8d %8d | %8s %8s %10s%n", label, n, tokLen * n, e.getClass().getSimpleName(), "-", "-");
    }
  }

  private org.arend.core.expr.Expression bodyOf(ModuleLocation module, String name) {
    Definition def = server.getResolvedDefinitions(module).stream()
        .filter(d -> d.definition().getData() instanceof TCDefReferable r && r.getRefName().equals(name))
        .map(d -> ((TCDefReferable) d.definition().getData()).getTypechecked())
        .filter(java.util.Objects::nonNull).findAny().orElse(null);
    return def instanceof org.arend.core.definition.FunctionDefinition fd && fd.getBody() instanceof org.arend.core.expr.Expression body ? body : null;
  }

  private static void runOnBigStack(Runnable r) {
    Throwable[] th = new Throwable[1];
    Thread thread = new Thread(null, () -> { try { r.run(); } catch (Throwable e) { th[0] = e; } }, "bench-bigstack", 512L * 1024 * 1024);
    thread.start();
    try { thread.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    if (th[0] instanceof RuntimeException re) throw re;
    if (th[0] instanceof Error er) throw er;
    if (th[0] != null) throw new RuntimeException(th[0]);
  }

  // BENCHMARK: the real println path. Typechecking `\func out => println (built)` triggers the
  // PrintMeta meta, which renders the argument via StringExpressionPrettifier -- the actual print
  // cost, not the toList proxy. Also prints the rendered string to the console, so we can eyeball
  // whether a recursively-built string prints its content (vs. falling back to raw-term printing).
  @Test
  public void benchmarkStringPrintln() throws IOException {
    StringBuilder tab = new StringBuilder();
    tab.append(String.format("%-12s %6s | %10s%n", "pattern", "N", "printlnMs"));
    for (int n : new int[]{200, 500}) {
      tab.append(printlnRow("prepend", "prependN " + n + " \"\" tok", n));
      tab.append(printlnRow("append",  "appendN "  + n + " \"\" tok", n));
    }
    tab.append(printlnRow("directchain", "\"ab\" ++ \"cd\" ++ \"ef\"", 3)); // sanity: direct ++ prints content
    Path out = Paths.get(System.getenv().getOrDefault("CLAUDE_JOB_DIR", "/tmp") + "/tmp/println-bench.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, tab.toString());
    System.out.println("\n===== PRINTLN BENCHMARK =====\n" + tab + "=============================\n");
  }

  private String printlnRow(String label, String buildExpr, int n) throws IOException {
    String src =
        "\\import Data.String\n\\import Debug.Meta\n" +
        "\\func appendN (k : Nat) (acc t : String) : String \\elim k | 0 => acc | suc k => appendN k (acc ++ t) t\n" +
        "\\func prependN (k : Nat) (acc t : String) : String \\elim k | 0 => acc | suc k => prependN k (t ++ acc) t\n" +
        "\\func tok => \"abcdefgh\"\n" +
        "\\func out => println (" + buildExpr + ")\n";
    String lib = "bench-println-" + label + n;
    Path srcDir = Files.createTempDirectory(lib);
    Files.writeString(srcDir.resolve("Snippet.ard"), src);
    libraryManager.updateLibrary(new FileSourceLibrary(lib, false, -1, List.of("arend-lib"), null,
        "org.arend.lib.StdExtension", null, srcDir, null, null,
        new FileClassLoaderDelegate(repoRoot.resolve("arend-lib").resolve("ext"))), server);
    ModuleLocation module = server.findModule(new ModulePath("Snippet"), lib, false, true);
    if (module == null) return String.format("%-12s %6d | %10s%n", label, n, "MOD_NULL");
    long[] ms = new long[1];
    try {
      runOnBigStack(() -> {
        long s = System.nanoTime();
        server.getCheckerFor(Collections.singletonList(module)).typecheck(UnstoppableCancellationIndicator.INSTANCE, ProgressReporter.empty());
        ms[0] = (System.nanoTime() - s) / 1_000_000;
      });
      return String.format("%-12s %6d | %10d%n", label, n, ms[0]);
    } catch (Throwable e) {
      return String.format("%-12s %6d | %10s%n", label, n, e.getClass().getSimpleName());
    }
  }
}
