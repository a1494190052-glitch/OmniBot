import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/widgets/common_app_bar.dart';

class TaskModifyPage extends StatelessWidget {
  final String? taskId;
  final String? type;
  final String? title;
  final String? dateTime;
  final String? tag;
  final String? content;
  final String? imagePath;
  final Map<String, dynamic>? payload;

  const TaskModifyPage({
    super.key,
    this.taskId,
    this.type,
    this.title,
    this.dateTime,
    this.tag,
    this.content,
    this.imagePath,
    this.payload,
  });

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: CommonAppBar(
        title: '任务修改',
        primary: true,
        showLeading: false,
        trailing: IconButton(
          icon: const Icon(Icons.close, color: Colors.black),
          onPressed: () => context.pop(),
        ),
      ),
      body: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 详细内容
            if (content != null)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Text(
                  content!,
                  style: const TextStyle(
                    color: Colors.black,
                    fontSize: 14,
                    height: 1.6,
                  ),
                ),
              ),
            
            const SizedBox(height: 100), // 底部留白
          ],
        ),
      ),
    );
  }
}
