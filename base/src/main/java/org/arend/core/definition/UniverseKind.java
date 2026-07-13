package org.arend.core.definition;

// TODO[sorts]: Delete this
public enum UniverseKind {
  NO_UNIVERSES, ONLY_COVARIANT, WITH_UNIVERSES;

  public UniverseKind max(UniverseKind other) {
    return ordinal() <= other.ordinal() ? other : this;
  }
}
