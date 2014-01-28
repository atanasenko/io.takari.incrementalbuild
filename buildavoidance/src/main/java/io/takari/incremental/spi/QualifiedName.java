package io.takari.incremental.spi;

import java.io.Serializable;

class QualifiedName implements Serializable {
  private static final long serialVersionUID = 8966369370744414886L;
  private final String qualifier;
  private final String localName;

  public QualifiedName(String qualifier, String localName) {
    this.qualifier = qualifier;
    this.localName = localName;
  }

  @Override
  public int hashCode() {
    int result = 31;
    result = result * 17 + qualifier.hashCode();
    result = result * 17 + localName.hashCode();
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof QualifiedName)) {
      return false;
    }
    QualifiedName other = (QualifiedName) obj;
    return qualifier.equals(other.qualifier) && localName.equals(other.localName);
  }

  public String getQualifier() {
    return qualifier;
  }

  public String getLocalName() {
    return localName;
  }
}