package dm.sent.voice.agents;

/**
 * The connection a session sends on: the agent gives each session one, and moves it on a resume.
 */
interface Sender {
    void sendText(String type, String json);

    void sendBytes(byte[] frame);
}
