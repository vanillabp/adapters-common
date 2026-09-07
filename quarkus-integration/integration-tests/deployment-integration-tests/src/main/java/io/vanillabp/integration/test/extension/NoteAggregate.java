package io.vanillabp.integration.test.extension;

/**
 * The workflow aggregate of the extension acceptance test. Its <code>touched</code>
 * attribute is what a handler method of the extension writes, so a test can tell whether
 * the aggregate was saved after the method ran.
 */
public class NoteAggregate {

  private String id;

  private String content;

  private String touched;

  public String getId() {

    return id;

  }

  public void setId(
      final String id) {

    this.id = id;

  }

  public String getContent() {

    return content;

  }

  public void setContent(
      final String content) {

    this.content = content;

  }

  public String getTouched() {

    return touched;

  }

  public void setTouched(
      final String touched) {

    this.touched = touched;

  }

}
