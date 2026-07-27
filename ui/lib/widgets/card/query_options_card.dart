import 'package:flutter/material.dart';
import '../../models/block_models.dart';
import '../normal_choices_group.dart';
import '../buttons_group_two.dart';
import '../bot_status.dart';

class QueryOptionsCard extends StatefulWidget {
  final QueryOptionsBlock block;
  final Function(ButtonModel)? onButtonConsumed;

  const QueryOptionsCard({
    Key? key,
    required this.block,
    this.onButtonConsumed,
  }) : super(key: key);

  @override
  State<QueryOptionsCard> createState() => _QueryOptionsCardState();
}

class _QueryOptionsCardState extends State<QueryOptionsCard>
    with TickerProviderStateMixin {
  late AnimationController _ctrl;
  late Animation<int> _countdown;
  late bool _executing;

  void _startCountdown() {
    if (!mounted) return;
    _ctrl.forward();
  }
  
  void onConfirm() {
    setState(() {
      _executing = false;
    });
    _ctrl.reset();
    print('Query confirmed');
  }

  void onCancel() {
    setState(() {
      _executing = false;
    });
    _ctrl.reset();
    print('Query cancelled');
  }

  void onButtonPressed(ButtonModel button) {
    if(button.action == 'confirm') {
      onConfirm();
    } else if(button.action == 'cancel') {
      onCancel();
    }
    widget.onButtonConsumed?.call(button);
  }
  
  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(vsync: this, duration: const Duration(seconds: 6));
    _countdown = IntTween(begin: 6, end: 0).animate(_ctrl);
    _executing = widget.block.countDown;

    _ctrl.addStatusListener((status) {
      if (mounted && status == AnimationStatus.completed) {
        onConfirm();
        widget.onButtonConsumed?.call(
          widget.block.buttonList.firstWhere(
            (button) => button.action == 'confirm',
            orElse: () => ButtonModel(action: 'errorConsume', text: ''),
          )
        );
      }
    });
    if(_executing) {
      _startCountdown();
    }
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // 获取取消和确认按钮，确认按钮设为primary
    final cancelButton = widget.block.buttonList.firstWhere(
      (button) => button.action == 'cancel',
      orElse: () => ButtonModel(action: 'cancel', text: ''),
    );
    final confirmButton = widget.block.buttonList.firstWhere(
      (button) => button.action == 'confirm',
      orElse: () => ButtonModel(action: 'confirm', text: ''),
    );

    return Column(
      children: [
        BotStatus(status: BotStatusType.hint, hintText: widget.block.title),
        const SizedBox(height: 8),
        Container(
          margin: const EdgeInsets.symmetric(vertical: 8.0),
          padding: const EdgeInsets.all(16.0),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(12.0),
            border: Border.all(color: Colors.grey.shade200),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (!widget.block.isConsumed)...[
                ButtonsGroupTwo(
                  countdownAnimation: _countdown,
                  isExecuting: _executing,
                  leftButton: cancelButton,
                  rightButton: confirmButton,
                  onButtonPressed: onButtonPressed,
                ),
              ]
            ],
          ),
        )
      ]
    );
  }
}
