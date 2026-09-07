package io.vanillabp.integration.test.extension;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of this scenario. Its <code>touched</code> attribute is what a
 * handler method of the extension writes, so a test can tell whether the aggregate was
 * saved after the method ran.
 */
@Entity
@Table(name = "NOTED_AGGREGATE")
@Getter
@Setter
public class NotedAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String content;

  private String touched;

}
