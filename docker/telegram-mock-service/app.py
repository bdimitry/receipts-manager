from flask import Flask, jsonify, request

app = Flask(__name__)
messages = []


@app.get("/health")
def health():
    return jsonify({"status": "UP"})


@app.post("/bot<token>/sendMessage")
def send_message(token):
    payload = request.get_json(silent=True) or {}
    message = {
        "token": token,
        "chat_id": str(payload.get("chat_id", "")),
        "text": str(payload.get("text", "")),
    }
    messages.append(message)
    return jsonify({"ok": True, "result": message})


@app.get("/messages")
def list_messages():
    return jsonify(messages)


@app.delete("/messages")
def clear_messages():
    messages.clear()
    return ("", 204)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8082)
