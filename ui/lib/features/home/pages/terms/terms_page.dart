import 'package:flutter/material.dart';
import 'package:ui/widgets/common_app_bar.dart';
// 条款页面
class TermsPage extends StatelessWidget {
  final String title;
  final String content;

  const TermsPage({super.key, required this.title, required this.content});

  @override
  Widget build(BuildContext context) {
    const darkGrey = Color(0xFF666666);
    
    return Scaffold(
      appBar: CommonAppBar(
        title: title,
        primary: true,
        onBackPressed: () {
          Navigator.pop(context);
        },
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: SingleChildScrollView(
          child: Text(
            content,
            style: TextStyle(fontSize: 12.0, color: darkGrey),
          ),
        ),
      ),
    );
  }
}
