package org.apache.spark.eventhubs.utils
import java.util.concurrent.CompletableFuture

class AadAuthenticationCallbackMock extends AadAuthenticationCallback {
  override def acquireToken(s: String, s1: String, o: Any): CompletableFuture[String] = {
    new CompletableFuture[String]()
  }

  override def authority: String = "Fake-tenant-id"
}

class AadAuthenticationCallbackMockWithParams(params: Map[String, Object])
    extends AadAuthenticationCallback {
  override def acquireToken(s: String, s1: String, o: Any): CompletableFuture[String] = {
    new CompletableFuture[String]()
  }

  override def authority: String = params("authority").asInstanceOf[String]
}
