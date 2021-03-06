package dev.sanda.datafi.persistence;

import java.io.Serializable;
import javax.persistence.Embeddable;
import lombok.NoArgsConstructor;

@lombok.Getter
@lombok.Setter
@Embeddable
@NoArgsConstructor
public class SimpleId implements Serializable {

  private Long id = IdFactory.getNextId();

  @Override
  public String toString() {
    return this.id.toString();
  }
}
