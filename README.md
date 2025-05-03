# P2P Secure Chat & File Transfer over UDP

This project implements a peer-to-peer (P2P) chat application in Java where nodes connect directly using UDP for communication. It features end-to-end encryption for chats and supports small file transfers between connected peers. A central coordination server facilitates peer discovery and connection setup.

## Features

* **Peer-to-Peer Communication:** Clients connect directly after initial coordination.
* **UDP Based:** Uses UDP for low-latency communication.
* **End-to-End Encryption (E2EE):** Chat messages and file data are encrypted using AES-GCM derived from an ECDH key exchange. Ensures only the communicating peers can read the content.
* **Coordination Server:** Helps nodes find each other and exchange necessary information (IP addresses, public keys) to establish a direct connection.
* **File Transfer:** Supports sending and receiving small files (up to 1 MiB by default) between connected peers with negotiation (accept/reject).
* **Basic UDP Reliability:** Implements a simple Stop-and-Wait acknowledgment mechanism for file transfer chunks.
* **Command-Line Interface (CLI):** Simple interface for interacting with the application.
* **Chat History:** Saves chat logs to local CSV files.
* **Downloads Folder:** Saves received files locally.

## Prerequisites

* **Java Development Kit (JDK):** Version 8 or higher recommended. Make sure `javac` and `java` commands are available in your system's PATH.
* **JSON Library:** A JSON parsing library is required. This project assumes you have `org.json.jar` (available from [MVNRepository](https://mvnrepository.com/artifact/org.json/json) or other sources).

## Setup

1.  **Clone/Download:** Get the project source code onto your local machine.
2.  **JSON Library:**
    * Create a directory named `lib` in the project's root folder (alongside `src`).
    * Download `json-YYYYMMDD.jar` (or similar) from a source like MVNRepository.
    * Rename the downloaded file to `json.jar` and place it inside the `lib` directory.

    Your project structure should look something like this:

    ```
    p2p-chat-udp/
    ├── lib/
    │   └── json.jar
    ├── src/
    │   └── main/
    │       └── java/
    │           └── com/
    │               └── p2pchat/
    │                   ├── common/
    │                   ├── node/
    │                   └── server/
    ├── chat_history/ (Will be created automatically)
    └── downloads/    (Will be created automatically)
    └── README.md     (This file)
    ```

## Compilation

1.  Open a terminal or command prompt in the project's root directory (`p2p-chat-udp`).
2.  Create a `bin` directory for the compiled `.class` files:
    ```bash
    mkdir bin
    ```
3.  Compile all Java source files, referencing the JSON library and outputting to the `bin` directory:

    * **Linux/macOS:**
        ```bash
        javac -cp lib/json.jar -d bin src/main/java/com/p2pchat/common/*.java src/main/java/com/p2pchat/node/**/*.java src/main/java/com/p2pchat/server/**/*.java
        ```
    * **Windows:**
        ```bash
        javac -cp "lib/json.jar" -d bin src/main/java/com/p2pchat/common/*.java src/main/java/com/p2pchat/node/**/*.java src/main/java/com/p2pchat/server/**/*.java
        ```
        *(Note: Windows might use semicolons `;` instead of colons `:` in classpath depending on the shell, but quotes often help)*

    This command compiles all `.java` files within the `common`, `node`, and `server` packages and their subpackages.

## Running the Application

You need to run the Coordination Server first, and then run two (or more) P2P Node clients.

**1. Running the Coordination Server**

* The server simply listens for clients. Run it in a dedicated terminal window.
* From the project root directory:

    * **Linux/macOS:**
        ```bash
        java -cp bin:lib/json.jar main.java.com.p2pchat.server.CoordinationServer
        ```
    * **Windows:**
        ```bash
        java -cp "bin;lib/json.jar" main.java.com.p2pchat.server.CoordinationServer
        ```
* You should see output indicating the server is listening, e.g., `[*] Coordination Server listening on UDP port 19999`.
* Keep this terminal window open while clients are running. Use Ctrl+C to stop the server.

**2. Running the P2P Node Client**

* Each client needs its own terminal window.
* You need to provide the IP address of the machine running the Coordination Server as a command-line argument.
* From the project root directory:

    * **If server is running on the *same* machine:**
        * Linux/macOS: `java -cp bin:lib/json.jar main.java.com.p2pchat.node.P2PNode 127.0.0.1`
        * Windows: `java -cp "bin;lib/json.jar" main.java.com.p2pchat.node.P2PNode 127.0.0.1`
    * **If server is running on a *different* machine (e.g., IP `A.B.C.D`):**
        * Linux/macOS: `java -cp bin:lib/json.jar main.java.com.p2pchat.node.P2PNode A.B.C.D`
        * Windows: `java -cp "bin;lib/json.jar" main.java.com.p2pchat.node.P2PNode A.B.C.D`
    * **If you omit the IP address, it defaults to `127.0.0.1`.**

* The client will start, generate keys, ask for a username, register with the server, and then present you with a command prompt.
* You will see your assigned **Node ID** after successful registration - you need this ID to connect to other peers.

## Using the Client (CLI Commands)

Once the client is running and registered (you see the `[?] Enter 'connect'...` prompt), you can use the following commands:

* **`id`**: Displays your username and unique Node ID. Share this ID with others so they can connect to you.
* **`status`** or **`s`**: Shows your current connection status (Disconnected, Waiting, Connected, etc.) and lists any ongoing file transfers.
* **`connect <peer_node_id>`**: Initiates a connection attempt to the specified peer using their Node ID.
    * Example: `connect e361fe63-254a-4ece-b555-425f8571ea2c`
    * Both peers need to execute the `connect` command with the *other's* ID for the server to match them.
* **`chat <message>`** or **`c <message>`**: Sends an encrypted chat message to the connected peer.
    * Example: `chat Hello there!`
* **(Default Input)**: When connected, simply typing text and pressing Enter (without a command like `chat` or `send`) will also send it as a chat message.
    * Example: `How are you?` (will be sent as a chat message)
* **`send <filepath>`**: Initiates sending a file to the connected peer. Provide the full or relative path to the file.
    * Example: `send my_document.txt`
    * Example: `send /Users/sanju/files/image.jpg`
    * The peer will receive an offer notification.
* **`accept <transfer_id>`**: Accepts an incoming file offer identified by `<transfer_id>`. The ID is shown when the offer arrives.
    * Example: `accept a1b2c3d4-e5f6-7890-abcd-ef1234567890`
* **`reject <transfer_id>`**: Rejects an incoming file offer.
    * Example: `reject a1b2c3d4-e5f6-7890-abcd-ef1234567890`
* **`disconnect`** or **`cancel`**:
    * If connected: Disconnects from the current peer.
    * If waiting for connection or attempting connection: Cancels the connection attempt.
* **`quit`** or **`exit`**: Shuts down the client application cleanly and returns you to the terminal prompt.

## Example Workflow

1.  **Start Server:** Run `CoordinationServer` in one terminal. Note its IP address if running clients on other machines.
2.  **Start Client 1:** Run `P2PNode <server_ip>` in a second terminal. Enter username "Alice". Note Alice's Node ID (e.g., `alice-id-123`).
3.  **Start Client 2:** Run `P2PNode <server_ip>` in a third terminal. Enter username "Bob". Note Bob's Node ID (e.g., `bob-id-456`).
4.  **Connect (Alice):** In Alice's terminal, type: `connect bob-id-456`
5.  **Connect (Bob):** In Bob's terminal, type: `connect alice-id-123`
6.  **Connection Establishes:** Both clients should show status updates indicating connection attempt and eventually "[+] SECURE E2EE CONNECTION ESTABLISHED...". The prompt will change to `[Chat 🔒 ...]`.
7.  **Chat (Alice):** Type `Hello Bob!` and press Enter.
8.  **Chat (Bob):** Bob sees "[Alice]: Hello Bob!". Bob types `Hi Alice!` and presses Enter.
9.  **Send File (Alice):** Alice creates a small file `test.txt`. In her terminal, she types: `send test.txt`. She sees an offer message being sent.
10. **Receive Offer (Bob):** Bob sees the incoming file offer notification with a transfer ID (e.g., `offer-id-789`).
11. **Accept File (Bob):** Bob types: `accept offer-id-789`. He sees a message indicating the file is being received.
12. **Transfer Progress:** Alice sees messages like "[FileTransfer:...] Sent chunk X/Y".
13. **Completion:** Both see completion messages. Bob finds `test.txt` inside the `downloads` folder in his project directory.
14. **Disconnect (Alice):** Alice types `disconnect`.
15. **Disconnect (Bob):** Bob sees a message that the peer has disconnected. Both prompts return to the disconnected state.
16. **Quit:** Both type `quit` to exit their clients.
17. **Stop Server:** Stop the `CoordinationServer` using Ctrl+C.

## Directories

* **`lib/`**: Contains the required `json.jar` library.
* **`src/`**: Contains all the Java source code.
* **`bin/`**: Contains the compiled `.class` files (created during compilation).
* **`chat_history/`**: Automatically created by clients. Stores `.csv` files containing chat logs for each peer connection. Files are named like `chat_<PeerUsername>_<PeerNodeID>.csv`.
* **`downloads/`**: Automatically created by clients. Stores files received via the file transfer feature.

## Technical Details

* **Networking:** Uses Java's `DatagramSocket` and `DatagramPacket` for UDP communication. Basic UDP hole punching (sending pings) is used to attempt direct P2P connection through NATs.
* **Encryption:**
    * Key Exchange: Elliptic Curve Diffie-Hellman (ECDH) using Java Cryptography Architecture (JCA/JCE).
    * Session Encryption: AES-GCM (256-bit) for authenticated encryption of chat messages and file chunks. Provides confidentiality and integrity.
* **Coordination:** The server acts solely as an introducer and does not relay any chat or file data.

## Limitations

* **UDP Unreliability:** UDP does not guarantee packet delivery or order for chat messages. Messages *might* be lost. File transfers use a simple acknowledgment mechanism, making them slow but more reliable than chat.
* **NAT Traversal:** Basic hole punching may not work through all types of NATs (especially symmetric NATs).
* **File Transfer Speed:** The Stop-and-Wait ACK mechanism for file transfers is simple but very slow compared to TCP or more advanced UDP reliability protocols. The file size limit (default 1 MiB) reflects this.
* **Server Single Point of Failure:** If the coordination server is down, new connections cannot be initiated.
* **Security:** Relies on trusting the coordination server during key exchange. No mechanism to verify peer identity beyond what the server provides.