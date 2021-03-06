package io.zrz.jpgsql.client.opj;

import java.io.InputStream;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;

import org.postgresql.copy.CopyIn;
import org.postgresql.jdbc.PgConnection;
import org.reactivestreams.Publisher;

import com.google.common.io.ByteSource;
import com.google.common.primitives.Ints;

import io.netty.buffer.ByteBuf;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableEmitter;
import io.reactivex.Single;
import io.reactivex.processors.UnicastProcessor;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.SingleSubject;
import io.zrz.jpgsql.client.AbstractQueryExecutionBuilder.Tuple;
import io.zrz.jpgsql.client.CommandStatus;
import io.zrz.jpgsql.client.NotifyMessage;
import io.zrz.jpgsql.client.PgSession;
import io.zrz.jpgsql.client.PostgresClient;
import io.zrz.jpgsql.client.PostgresqlUnavailableException;
import io.zrz.jpgsql.client.Query;
import io.zrz.jpgsql.client.QueryParameters;
import io.zrz.jpgsql.client.QueryResult;
import io.zrz.jpgsql.client.SessionTxnState;
import io.zrz.sqlwriter.SqlWriters;
import lombok.SneakyThrows;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * a single seized connection, outside the scope of a transaction (although transactions may be started).
 */

@Slf4j
class PgSingleSession implements Runnable, PgSession {

  @Value
  private static class Work {
    private Query query;
    private QueryParameters params;
    private FlowableEmitter<QueryResult> emitter;
    private Object source;
  }

  private static final Duration LOOP_WAIT = Duration.ofSeconds(1);
  private static final Duration MAX_IDLE = Duration.ofSeconds(5);

  private final SingleSubject<SessionTxnState> txnstate = SingleSubject.create();
  private final LinkedTransferQueue<Work> workqueue = new LinkedTransferQueue<>();

  // if we are accepting work still
  private boolean accepting = true;
  private final PgThreadPooledClient pool;

  PgSingleSession(PgThreadPooledClient pool) {
    this.pool = pool;
  }

  @Override
  public Flowable<QueryResult> submit(Query query, QueryParameters params) {

    if (!this.accepting) {
      throw new IllegalStateException(String.format("This session is no longer active"));
    }

    final Flowable<QueryResult> flowable = Flowable.create(emitter -> {

      log.debug("added work item: {}", query);
      this.workqueue.add(new Work(query, params, emitter, null));

    }, BackpressureStrategy.BUFFER);

    return flowable
        .publish()
        .autoConnect()
        .subscribeOn(Schedulers.io(), true)
        .observeOn(Schedulers.io(), true)
        .doOnEach(e -> log.debug("notif: {}", e));

  }

  @Override
  public Publisher<Long> copyTo(String sql, Publisher<ByteBuf> data) {

    log.debug("starting COPY TO: {}", sql);

    if (!this.accepting) {
      throw new IllegalStateException(String.format("This session is no longer active"));
    }

    final Flowable<QueryResult> flowable = Flowable.create(emitter -> {

      log.debug("starting COPY");
      this.workqueue.add(new Work(this.createQuery(sql), null, emitter, data));

    }, BackpressureStrategy.BUFFER);

    return flowable
        .publish()
        .autoConnect()
        .doOnEach(e -> log.debug("notif: {}", e))
        .map(x -> (long) (((CommandStatus) x).getUpdateCount()))
        .subscribeOn(Schedulers.io(), true)
        .observeOn(Schedulers.io(), true)
        .singleOrError()
        .toFlowable();

  }

  @Override
  public Publisher<Long> copyTo(String sql, ByteSource source) {

    log.debug("starting COPY TO: {}", sql);

    if (!this.accepting) {
      throw new IllegalStateException(String.format("This session is no longer active"));
    }

    final Flowable<QueryResult> flowable = Flowable.create(emitter -> {

      log.debug("starting COPY");
      this.workqueue.add(new Work(this.createQuery(sql), null, emitter, source));

    }, BackpressureStrategy.BUFFER);

    return flowable
        .publish()
        .autoConnect()
        .doOnEach(e -> log.debug("notif: {}", e))
        .map(x -> (long) (((CommandStatus) x).getUpdateCount()))
        .subscribeOn(Schedulers.io(), true)
        .observeOn(Schedulers.io(), true)
        .singleOrError()
        .toFlowable();
  }

  /*
   * run in the thread with the connection any exception propogated from here will dispatch an onError on the txnstate.
   */

  private void run(PgLocalConnection conn) throws SQLException, InterruptedException {

    log.debug("starting session");

    conn.getConnection().setAutoCommit(false);

    Instant startidle = Instant.now();

    while (true) {

      final Work work = this.workqueue.poll(100, TimeUnit.MILLISECONDS);

      if (work != null) {

        pollIfNeeded(conn, 1);

        if (work.getQuery() == null && work.getEmitter() == null) {

          log.debug("single session finished");

          switch (conn.transactionState()) {
            case IDLE:
              return;
            case FAILED:
              log.warn("rolling back");
              conn.rollback();
              return;
            case OPEN:
              log.warn("rolling back");
              conn.rollback();
              return;
          }

          return;

        }
        else if (work.getEmitter() == null) {

          log.debug("no emitter - rolling back, work was {}", work);
          conn.rollback();
          return;

        }
        else if (work.getSource() != null) {

          String sql = work.getQuery().statement(0).sql();

          log.info("starting {}", sql);

          try {

            long value = processCopy(conn.getConnection(), sql, work.getSource())
                .blockingGet();

            log.info("copy completed {}", value);

            work.emitter.onNext(new CommandStatus(0, "COPY", Ints.checkedCast(value), 0));

            work.emitter.onComplete();

            pollIfNeeded(conn, -1);

          }
          catch (Throwable t) {

            log.warn("copy error: {}", t.getMessage(), t);
            conn.rollback();
            this.accepting = false;
            work.emitter.onError(t);

          }
          finally {

            log.debug("copy finished");

          }

        }
        else {

          log.debug("processing work item {}", work);

          startidle = null;

          conn.execute(work.getQuery(), work.getParams(), work.getEmitter(), 0, PgLocalConnection.SuppressBegin);

          log.debug("query completed");

          pollIfNeeded(conn, -1);

        }

        startidle = Instant.now();

      }
      else {

        // idle ...
        pollIfNeeded(conn, 1);

      }

      switch (conn.transactionState()) {
        case IDLE:
          // this.accepting = false;
          // this.txnstate.onSuccess(SessionTxnState.Closed);
          // if (!this.workqueue.isEmpty()) {
          // log.warn("work queue is not empty after session completed");
          // this.workqueue.forEach(e -> {
          // if (e.emitter != null)
          // e.emitter.onError(new IllegalStateException("Session has already completed (in IDLE)"));
          // });
          // }
          break;
        case FAILED:
          log.trace("txn state now {}", conn.transactionState());
          this.accepting = false;
          this.txnstate.onSuccess(SessionTxnState.Error);
          if (!this.workqueue.isEmpty()) {
            log.warn("work queue is not empty after session completed");
            this.workqueue.forEach(e -> {
              if (e.emitter != null)
                e.emitter.onError(new IllegalStateException("Session has already completed (in FAILED) for " + e.getQuery()));
            });
          }
          return;
        case OPEN:
          log.trace("txn state now {}", conn.transactionState());
          if (!this.accepting && this.workqueue.isEmpty()) {
            // rollback - which will terminate us.
            log.info("rolling back");
            conn.rollback();
          }
          // still going ..
          break;
      }

    }

  }

  private void pollIfNeeded(PgLocalConnection conn, int i) {

    if (listeners.isEmpty()) {
      return;
    }

    for (NotifyMessage n : conn.notifications(i)) {

      UnicastProcessor<NotifyMessage> p = this.listeners.get(n.channel());

      if (p == null) {
        log.warn("notification for unknown channel {}", n.channel());
        continue;
      }

      log.debug("got notification for {}", n.channel());

      p.onNext(n);

    }

  }

  @SneakyThrows
  private Single<Long> processCopy(PgConnection conn, String sql, Object source) {

    if (source instanceof ByteSource) {

      ByteSource ssource = ((ByteSource) source);

      ByteSource preamble = ByteSource.wrap(PgThreadPooledClient.BINARY_PREAMBLE);

      try (InputStream in = ByteSource.concat(preamble, ssource).openBufferedStream()) {

        return Single.just(Long.valueOf(conn.getCopyAPI().copyIn(sql, in)));

      }
      catch (Throwable t) {

        return Single.error(t);

      }

    }

    CopyIn copy = conn.getCopyAPI().copyIn(sql);

    // PGCopyOutputStream out = new PGCopyOutputStream(copy, 1024 * 1024 * 64);

    copy.writeToCopy(PgThreadPooledClient.BINARY_PREAMBLE, 0, PgThreadPooledClient.BINARY_PREAMBLE.length);

    return Flowable.fromPublisher((Publisher<ByteBuf>) source)

        .doOnNext(buf -> {

          while (buf.isReadable()) {
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            copy.writeToCopy(out, 0, out.length);
          }

          buf.release();

        })
        .ignoreElements()
        .andThen(Single.defer(() -> {

          log.debug("closing copy stream");

          long len = copy.endCopy();

          log.debug("CopyIn finished, {} rows", len);

          return Single.just(len);

        }));

  }

  /*
   * called when the job is allocated - runs in the thread.
   */

  @Override
  public void run() {
    log.trace("running query");
    try {
      this.run(PgConnectionThread.connection());
    }
    catch (final SQLException ex) {
      // Any propagated SQLException results in the connection being closed.
      log.warn("connection failed: {}", ex.getMessage(), ex);
      this.accepting = false;
      PgConnectionThread.close();
      this.txnstate.onError(new PostgresqlUnavailableException(ex));
    }
    catch (final Exception ex) {
      log.warn("connection failed: {}", ex.getMessage(), ex);
      this.accepting = false;
      // TODO: release the transaction, but don't close the connection.
      this.txnstate.onError(ex);
    }
  }

  /*
   * rollback the transaction if there is one.
   */

  @Override
  public void close() {
    // Preconditions.checkState(this.accepting, "session is no longer active");
    // if (accepting) {
    // this.accepting = false;
    // this.workqueue.add(new Work(this.pool.createQuery("ROLLBACK"), null, null, null));
    // }

    log.debug("closing single session");
    this.accepting = false;
    this.workqueue.add(new Work(null, null, null, null));

  }

  /*
   * called from the consumer thread. jusr raise the failure, nothing else.
   */

  public void failed(Exception ex) {
    this.accepting = false;
    this.txnstate.onError(ex);
  }

  @Override
  public Query createQuery(String sql, int paramcount) {
    return this.pool.createQuery(sql, paramcount);
  }

  @Override
  public Query createQuery(List<Query> combine) {
    return this.pool.createQuery(combine);
  }

  @Override
  public Flowable<QueryResult> fetch(int batchSize, Tuple query) {
    throw new IllegalArgumentException();
  }

  @Override
  public Publisher<NotifyMessage> listen(String channel) {
    UnicastProcessor<NotifyMessage> listener = UnicastProcessor.create();
    this.listeners.put(channel, listener);
    Flowable.fromPublisher(this.submit(SqlWriters.listen(channel)))
        .subscribe(msg -> {
          log.debug("subscribed to {}", channel);
        }, err -> {
          log.warn("notification error: {}", err);
          UnicastProcessor<NotifyMessage> ch = listeners.remove(channel);
          ch.onError(err);
        });
    return listener;
  }

  private Map<String, UnicastProcessor<NotifyMessage>> listeners = new HashMap<>();

  @Override
  public PostgresClient client() {
    return pool;
  }

}
