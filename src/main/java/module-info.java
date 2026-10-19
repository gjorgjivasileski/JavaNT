module dev.gorgi.javafxnotes {
    requires javafx.controls;
    requires javafx.web;
    requires java.xml;
    requires jdk.xml.dom;
    requires jdk.jsobject;
    requires org.commonmark;
    requires org.commonmark.ext.gfm.tables;
    requires org.commonmark.ext.gfm.strikethrough;
    requires org.commonmark.ext.task.list.items;
    exports dev.gorgi.notes to javafx.graphics;
}
