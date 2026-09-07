package io.vanillabp.integration.test.extension;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A second workflow aggregate whose workflow service has no method of the extension and
 * whose extension service nobody injects - the proof that both are optional.
 */
@Entity
@Table(name = "UNNOTED_AGGREGATE")
@Getter
@Setter
public class UnnotedAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String content;

}
