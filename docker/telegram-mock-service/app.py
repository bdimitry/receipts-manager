import itertools
from flask import Flask, jsonify, request

app = Flask(__name__)
messages = []
documents = []
updates = []
update_sequence = itertools.count(1)


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
    uploaded_file = request.files.get("document")
    document = {
        "token": token,
        "chat_id": str(request.form.get("chat_id", "")),
        "caption": str(request.form.get("caption", "")),
        "file_name": uploaded_file.filename if uploaded_file else "",
        "content_type": uploaded_file.mimetype if uploaded_file else "",
        "size": len(uploaded_file.read()) if uploaded_file else 0,
    }
    documents.append(document)
    return jsonify({"ok": True, "result": document})


@app.get("/bot<token>/getUpdates")
def get_updates(token):
    offset = request.args.get("offset", type=int)
    limit = request.args.get("limit", default=100, type=int)
    filtered = [update for update in updates if update["token"] == token]
    if offset is not None:
        filtered = [update for update in filtered if update["update_id"] >= offset]
    return jsonify({"ok": True, "result": filtered[:limit]})


@app.get("/messages")
def list_messages():
    return jsonify(messages)


@app.get("/documents")
def list_documents():
    return jsonify(documents)


@app.post("/updates")
def enqueue_update():
    payload = request.get_json(silent=True) or {}
    update = {
        "token": str(payload.get("token", "")),
        "update_id": next(update_sequence),
        "message": {
            "text": str(payload.get("text", "")),
            "chat": {"id": str(payload.get("chat_id", ""))},
        },
    }
    updates.append(update)
    return jsonify(update), 201


@app.delete("/messages")
def clear_messages():
    messages.clear()
    return ("", 204)


@app.delete("/documents")
def clear_documents():
    documents.clear()
    return ("", 204)


@app.delete("/updates")
def clear_updates():
    updates.clear()
    global update_sequence
    update_sequence = itertools.count(1)
    return ("", 204)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8082)
