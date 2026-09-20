import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:webview_flutter/webview_flutter.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      systemNavigationBarColor: Colors.white,
    ),
  );
  runApp(const LumiApp());
}

class LumiApp extends StatelessWidget {
  const LumiApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Lumi',
      theme: ThemeData(
        useMaterial3: true,
        scaffoldBackgroundColor: Colors.white,
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF4D6BFE)),
      ),
      home: const ChatScreen(),
    );
  }
}

class ChatScreen extends StatefulWidget {
  const ChatScreen({super.key});

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  late final WebViewController _controller;

  @override
  void initState() {
    super.initState();
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.white)
      ..addJavaScriptChannel('FlutterLumi', onMessageReceived: _onHttpRequest)
      ..loadFlutterAsset('assets/index.html');
  }

  // Performs an HTTP request on behalf of the WebView so streaming chat
  // completions and /models calls are not blocked by browser CORS.
  Future<void> _onHttpRequest(JavaScriptMessage message) async {
    Map<String, dynamic> cfg;
    try {
      cfg = jsonDecode(message.message) as Map<String, dynamic>;
    } catch (_) {
      return;
    }
    final id = (cfg['id'] ?? '').toString();
    final url = (cfg['url'] ?? '').toString();
    final method = (cfg['method'] ?? 'POST').toString();
    final body = (cfg['body'] ?? '').toString();
    final headers = (cfg['headers'] as Map?)?.map(
          (k, v) => MapEntry(k.toString(), v.toString()),
        ) ??
        <String, String>{};

    final client = http.Client();
    try {
      final req = http.Request(method, Uri.parse(url));
      req.headers.addAll(headers);
      if (body.isNotEmpty) req.body = body;

      final resp = await client.send(req);
      if (resp.statusCode < 200 || resp.statusCode >= 300) {
        final text = await resp.stream.bytesToString();
        _emit('onError', id, 'HTTP ${resp.statusCode}: ${_clip(text)}');
        return;
      }
      await for (final chunk in resp.stream.transform(utf8.decoder)) {
        if (!mounted) break;
        _emit('onDelta', id, chunk);
      }
      _emit('onEnd', id, null);
    } catch (e) {
      _emit('onError', id, e.toString());
    } finally {
      client.close();
    }
  }

  void _emit(String fn, String id, String? arg) {
    final a = arg == null ? 'null' : jsonEncode(arg);
    _controller
        .runJavaScript('window.__lumi && window.__lumi.$fn(${jsonEncode(id)}, $a);')
        .catchError((_) {});
  }

  String _clip(String s) => s.length > 300 ? '${s.substring(0, 300)}…' : s;

  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      body: WebViewWidget(controller: _controller),
    );
  }
}
