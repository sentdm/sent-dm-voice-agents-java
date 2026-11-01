# Sent Voice Agents for Java

An AI agent that takes and places phone calls through [Sent](https://sent.dm), on the
[Sent Voice Agents protocol](https://github.com/sentdm/sent-dm-voice-protocol) 0.2.0, for Java 17
and later.

Status: Preview, with the protocol. Generated and rendered by `sent-dm-sdk-pipeline` from the protocol at
`v0.2.0`; every release passes the protocol's conformance scenarios in CI.

Read the [Voice Agents docs](https://docs.sent.dm) to get started.

## Install

```kotlin
// Gradle
implementation("dm.sent:voice-agents:<version>")
```

```xml
<!-- Maven -->
<dependency>
  <groupId>dm.sent</groupId>
  <artifactId>voice-agents</artifactId>
  <version>...</version>
</dependency>
```

Java 17 or later. The connection is the JDK's `java.net.http.WebSocket`; the one dependency is
`jackson-databind`, for the frames.

## Quickstart

```java
import dm.sent.voice.agents.Agent;
import dm.sent.voice.agents.Handler;
import dm.sent.voice.agents.Options;
import dm.sent.voice.agents.Session;
import dm.sent.voice.agents.frames.UserSaid;

public class Support implements Handler {
    @Override
    public void onSessionStart(Session session) {
        session.say("Hi, this is Acme support. How can I help?");
    }

    @Override
    public void onUserSaid(Session session, UserSaid said) {
        if (said.text().contains("person")) {
            session.say("Transferring you now.");
            session.transfer("+14155550100").join();
        } else {
            session.say("You said " + said.text());
        }
    }

    public static void main(String[] args) {
        Options options = new Options(System.getenv("SENT_API_KEY"), "agent_...");
        new Agent(options, new Support()).serve();
    }
}
```

`serve()` connects to Sent with the account API key and serves calls until SIGINT or SIGTERM; `run()` does the
same and returns a future that completes on `stop()`. Each session's handlers run in order on their own chain,
so sessions never wait for one another, and a handler may block on a tool's future without holding the others
up. The runtime answers heartbeats, opens a new connection when one drains or is lost, and resumes the sessions
that can move; the handlers only see calls.

| Handler                              | When                                                                                           |
| ------------------------------------ | ---------------------------------------------------------------------------------------------- |
| `onSessionStart(session)`            | A call reached the agent. In a text session, `say` to speak                                    |
| `onUserSaid(session, said)`          | Text sessions: the caller said something; `said.correction()` means it replaces the previous one |
| `onAudio(session, samples)`          | Audio sessions: the caller's audio, 16-bit mono PCM at `session.sampleRate()`                  |
| `onToolResult(session, result)`      | Sent answered a tool call; the waiting future gets the same result                             |
| `onSessionEnd(session, end)`         | The call left the agent; `end.reason()` says why                                               |
| `onSessionLost(session)`             | The connection was lost and the session could not be resumed                                   |

On a `Session`: `say(text)`, `say(text, last, interruptible)`, `clear()`, `sendAudio(samples)`, and the tools
`transfer(to)`, `hangup()`, `sendDtmf(digits)`, `hold(held)`, each a `CompletableFuture` of Sent's `ToolResult`.
`session.from()`, `to()`, `direction()`, `sessionType()`, `language()`, `context()`, `sampleRate()` carry what
`session.start` said.

The `frames` package holds every frame as a record, with `Protocol.decode()` and `Protocol.encode()` that check
the protocol's rules; it is generated from the protocol and not edited by hand.

## Develop

```sh
gradle spotlessCheck build          # formatting, the library, the tests against the protocol's fixtures
gradle :conformance:installDist     # the conformance agent the protocol's action runs
```

Gradle 8.14 and a JDK 17; `gradle:8-jdk17` is a container that has both. The conformance scenarios run in CI
through the protocol's action, against the `conformance` program. Releases are cut by release-please from
`main` and published to Maven Central from GitHub Actions, signed, with the Central Portal token in the
repository's secrets.
