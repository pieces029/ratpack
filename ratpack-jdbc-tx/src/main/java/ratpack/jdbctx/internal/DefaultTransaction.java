/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.jdbctx.internal;

import ratpack.exec.Blocking;
import ratpack.exec.Downstream;
import ratpack.exec.Operation;
import ratpack.exec.Promise;
import ratpack.func.Action;
import ratpack.func.Factory;
import ratpack.jdbctx.Transaction;
import ratpack.util.Exceptions;

import java.sql.Connection;
import java.sql.Savepoint;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

public class DefaultTransaction implements Transaction {

  private final Deque<Savepoint> savepoints = new ArrayDeque<>(1);
  private final Factory<? extends Connection> connectionFactory;

  private volatile Connection connection;
  private boolean autoBind = true;

  public DefaultTransaction(Factory<? extends Connection> connectionFactory) {
    this.connectionFactory = connectionFactory;
  }

  @Override
  public Optional<Connection> getConnection() {
    return Optional.ofNullable(connection);
  }

  @Override
  public <T> Promise<T> wrap(Promise<T> promise) {
    return Promise.flatten(() ->
      begin()
        .flatMap(
          promise.transform(up -> down ->
            up.connect(new Downstream<T>() {
              @Override
              public void success(T value) {
                commit()
                  .onError(down::error)
                  .then(() -> down.success(value));
              }

              @Override
              public void error(Throwable throwable) {
                rollback()
                  .onError(Action.suppressAndThrow(throwable))
                  .then(() -> down.error(throwable));
              }

              @Override
              public void complete() {
                commit()
                  .onError(down::error)
                  .then(down::complete);
              }
            })
          )
        )
    );
  }

  @Override
  public Operation wrap(Operation operation) {
    return wrap(operation.promise()).operation();
  }

  @Override
  public Transaction autoBind(boolean autoBind) {
    this.autoBind = true;
    return this;
  }

  @Override
  public boolean isAutoBind() {
    return autoBind;
  }

  @Override
  public Operation begin() {
    return Operation.of(() -> {
      if (connection == null) {
        if (autoBind) {
          bind();
        }
        Blocking.op(() -> {
          connection = connectionFactory.create();
          connection.setAutoCommit(false);
        })
          .onError(e ->
            dispose(Action.noop())
              .onError(Action.suppressAndThrow(e))
              .then()
          )
          .then();
      } else {
        Blocking.get(connection::setSavepoint)
          .then(savepoints::push);
      }
    });
  }

  @Override
  public Operation rollback() {
    return Operation.of(() -> {
      if (connection == null) {
        throw new IllegalStateException("Rollback attempted outside of a transaction.");
      }
      Savepoint savepoint = savepoints.poll();
      if (savepoint == null) {
        dispose(Connection::rollback).then();
      } else {
        Blocking.op(() -> connection.rollback(savepoint)).then();
      }
    });
  }

  @Override
  public Operation commit() {
    return Operation.of(() -> {
      if (connection == null) {
        throw new IllegalStateException("Commit attempted outside of a transaction.");
      }
      Savepoint savepoint = savepoints.poll();
      if (savepoint == null) {
        dispose(Connection::commit).then();
      }
    });
  }

  private Operation dispose(Action<? super Connection> disposal) {
    return Blocking.op(() -> {
      if (connection != null) {
        try (Connection c = connection) {
          disposal.execute(c);
        }
      }
    }).onError(e -> {
      connection = null;
      throw Exceptions.toException(e);
    }).next(() -> {
      connection = null;
      if (autoBind) {
        unbind();
      }
    });
  }

}
