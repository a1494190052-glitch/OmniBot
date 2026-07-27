import 'dart:async';
import 'package:flutter/material.dart';
import 'package:fluttertoast/fluttertoast.dart';
import 'package:ui/widgets/bot_status.dart';
import 'package:ui/widgets/card/edit_task_card.dart';
import 'package:ui/features/task/pages/execution_history/run_log_list_page.dart';
import 'package:ui/services/task_storage_service.dart';
import 'package:ui/models/task_models.dart';
import 'package:ui/widgets/common_app_bar.dart';

class TaskEditPage extends StatefulWidget {
  final String taskId;

  const TaskEditPage({Key? key, required this.taskId}) : super(key: key);

  @override
  State<TaskEditPage> createState() => _TaskEditPageState();
}

class _TaskEditPageState extends State<TaskEditPage>
    with WidgetsBindingObserver {
  late TaskData task;
  late String taskTitle;
  late DateTime taskDate;
  late TimeOfDay taskTime;
  late RepeatOption repeatOption;

  bool _isLoading = true;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _initializeTask();
  }

  /// 初始化任务数据
  Future<void> _initializeTask() async {
    try {
      setState(() {
        _isLoading = true;
        _errorMessage = null;
      });

      final loadedTask = await TaskStorageService.getTaskById(widget.taskId);
      if (loadedTask == null) {
        _errorMessage = '未找到任务数据';
        initFailed();
        return;
      }

      task = loadedTask;
      taskTitle = task.title;
      taskDate = task.date;
      taskTime = task.time;
      repeatOption = task.repeatOption;
    } catch (e) {
      _errorMessage = '获取任务数据时发生错误: $e';
      initFailed();
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  void initFailed() {
    print(_errorMessage);
    Fluttertoast.showToast(msg: '任务数据加载失败');
    Navigator.of(context).pop();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  void onDateChanged(DateTime newDate) {
    setState(() {
      taskDate = newDate;
    });
  }

  void onTimeChanged(TimeOfDay newTime) {
    setState(() {
      taskTime = newTime;
    });
  }

  void onRepeatOptionChanged(RepeatOption newOption) {
    setState(() {
      repeatOption = newOption;
    });
  }

  void saveTask() async {
    // 保存任务
    final newTask = TaskData(
      id: widget.taskId,
      title: taskTitle,
      date: taskDate,
      time: taskTime,
      repeatOption: repeatOption,
      isEnabled: task.isEnabled,
      createdAt: task.createdAt,
      updatedAt: DateTime.now(),
    );

    print(
      "try save task: id=${newTask.id}, title=${newTask.title}, date=${newTask.date}, time=${newTask.time}, repeat=${newTask.repeatOption}",
    );

    try {
      setState(() {
        _isLoading = true;
        _errorMessage = null;
      });

      final success = await TaskStorageService.saveTask(newTask);
      if (!success) {
        _errorMessage = '本地任务保存失败';
        saveFailed();
        return;
      }

      task = newTask;
      taskTitle = task.title;
      taskDate = task.date;
      taskTime = task.time;
      repeatOption = task.repeatOption;

      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('任务保存成功'), backgroundColor: Colors.green),
      );

      Future.delayed(const Duration(seconds: 1), () {
        if (mounted) {
          Navigator.of(context).pop(true);
        }
      });
    } catch (e) {
      _errorMessage = '保存任务数据时发生错误: $e';
      saveFailed();
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  void saveFailed() {
    print(_errorMessage);
    Fluttertoast.showToast(msg: '任务数据保存失败');
  }

  @override
  Widget build(BuildContext context) {
    // 加载中状态
    if (_isLoading) {
      return Scaffold(
        appBar: const CommonAppBar(title: '加载中...', primary: true),
        body: const Center(child: CircularProgressIndicator()),
      );
    }

    return Scaffold(
      appBar: CommonAppBar(
        title: taskTitle,
        primary: true,
        trailing: TextButton(
          onPressed: () {
            // 跳转到编辑页面
            Navigator.push(
              context,
              MaterialPageRoute(builder: (_) => const RunLogListPage()),
            );
          },
          child: const Text('执行历史'),
        ),
      ),
      body: Padding(
        padding: EdgeInsetsGeometry.symmetric(horizontal: 16),
        child: Column(
          children: [
            SizedBox(height: 50),
            BotStatus(status: BotStatusType.hint, hintText: '请编辑任务内容。'),
            SizedBox(height: 15),
            EditTaskCard(
              selectedDate: taskDate,
              selectedTime: taskTime,
              repeatOption: repeatOption,
              onDateChanged: onDateChanged,
              onTimeChanged: onTimeChanged,
              onRepeatChanged: onRepeatOptionChanged,
              onSave: saveTask,
            ),
          ],
        ),
      ),
    );
  }
}
