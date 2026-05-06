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


@app.post("/bot<token>/sendDocument")
def send_document(token):
    document = request.files.get("document")
    content = document.read() if document else b""
    message = {
        "token": token,
        "chat_id": str(request.form.get("chat_id", "")),
        "text": "",
        "caption": str(request.form.get("caption", "")),
        "document_file_name": document.filename if document else "",
        "document_content_type": document.content_type if document else "",
        "document_size": len(content),
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
