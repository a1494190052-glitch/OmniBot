import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/services/account_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

class AccountPage extends StatefulWidget {
  const AccountPage({super.key});

  @override
  State<AccountPage> createState() => _AccountPageState();
}

class _AccountPageState extends State<AccountPage> {
  final _formKey = GlobalKey<FormState>();
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  final _confirmPasswordController = TextEditingController();
  final _verificationCodeController = TextEditingController();

  bool _loading = true;
  bool _busy = false;
  bool _registerMode = false;
  bool _showPassword = false;
  AccountSessionState? _session;
  AccountOverview? _overview;
  RegistrationCodeRequest? _codeRequest;
  String? _codeRequestEmail;
  String? _error;

  bool get _english => Localizations.localeOf(context).languageCode != 'zh';

  String _text(String zh, String en) => _english ? en : zh;

  @override
  void initState() {
    super.initState();
    _loadAccount();
  }

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    _confirmPasswordController.dispose();
    _verificationCodeController.dispose();
    super.dispose();
  }

  Future<void> _loadAccount() async {
    if (mounted) {
      setState(() {
        _loading = true;
        _error = null;
      });
    }
    try {
      final session = await AccountService.getSessionState();
      AccountOverview? overview;
      if (session.configured && session.signedIn) {
        overview = await AccountService.getOverview();
      }
      if (!mounted) return;
      setState(() {
        _session = session;
        _overview = overview;
      });
    } on PlatformException catch (error) {
      if (!mounted) return;
      if (error.code == 'NOT_AUTHENTICATED' ||
          error.code == 'invalid_refresh_token') {
        setState(() {
          _session = const AccountSessionState(
            configured: true,
            signedIn: false,
          );
          _overview = null;
        });
      } else {
        setState(() => _error = _messageFor(error));
      }
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _error = _text('账号功能暂时不可用，请稍后重试', 'Account is temporarily unavailable');
      });
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _sendVerificationCode() async {
    final email = _emailController.text.trim();
    if (!_looksLikeEmail(email)) {
      setState(() => _error = _text('请先填写正确的邮箱', 'Enter a valid email first'));
      return;
    }
    await _withBusy(() async {
      final request = await AccountService.requestRegistrationCode(email);
      if (!mounted) return;
      setState(() {
        _codeRequest = request;
        _codeRequestEmail = email;
        _error = null;
      });
      _showSuccessToast(
        _text(
          '验证码已发送，${request.expiresInSeconds ~/ 60} 分钟内有效',
          'Code sent and valid for ${request.expiresInSeconds ~/ 60} minutes',
        ),
      );
    });
  }

  Future<void> _submitAuth() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    final email = _emailController.text.trim();
    final password = _passwordController.text;
    final creatingAccount = _registerMode;
    await _withBusy(() async {
      if (creatingAccount) {
        final request = _codeRequest;
        if (request == null || _codeRequestEmail != email) {
          throw PlatformException(
            code: 'CODE_NOT_REQUESTED',
            message: _text(
              '请为当前邮箱重新发送验证码',
              'Request a verification code for this email first',
            ),
          );
        }
        await AccountService.register(
          email: email,
          password: password,
          verificationRequestId: request.requestId,
          verificationCode: _verificationCodeController.text.trim(),
        );
      }
      try {
        await AccountService.login(email: email, password: password);
      } catch (_) {
        if (creatingAccount && mounted) {
          setState(() => _registerMode = false);
        }
        rethrow;
      }
      _passwordController.clear();
      _confirmPasswordController.clear();
      _verificationCodeController.clear();
      _codeRequest = null;
      _codeRequestEmail = null;
      _registerMode = false;
      await _loadAccount();
      if (mounted) {
        _showSuccessToast(
          creatingAccount
              ? _text('注册并登录成功', 'Account created and signed in')
              : _text('登录成功', 'Signed in'),
        );
      }
    });
  }

  Future<void> _changeMode(AiAccessMode mode) async {
    final overview = _overview;
    if (overview == null || overview.settings.mode == mode) return;
    await _withBusy(() async {
      final settings = await AccountService.updateAiMode(mode);
      if (!mounted) return;
      setState(() {
        _overview = AccountOverview(user: overview.user, settings: settings);
      });
      _showSuccessToast(_text('AI 来源已更新', 'AI source updated'));
    });
  }

  Future<void> _logout() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(_text('退出登录', 'Sign out')),
        content: Text(
          _text('只会退出当前设备，其他设备不受影响。', 'Only this device will be signed out.'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(_text('取消', 'Cancel')),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(_text('退出', 'Sign out')),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    await _withBusy(() async {
      try {
        await AccountService.logout();
      } finally {
        if (mounted) {
          setState(() {
            _session = const AccountSessionState(
              configured: true,
              signedIn: false,
            );
            _overview = null;
          });
        }
      }
    });
  }

  Future<void> _withBusy(Future<void> Function() operation) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await operation();
    } on PlatformException catch (error) {
      if (mounted) setState(() => _error = _messageFor(error));
    } catch (_) {
      if (mounted) {
        setState(() {
          _error = _text('操作失败，请稍后重试', 'Operation failed. Try again later.');
        });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  String _messageFor(PlatformException error) {
    switch (error.code) {
      case 'invalid_credentials':
        return _text('邮箱或密码不正确', 'Incorrect email or password');
      case 'email_already_registered':
        return _text('这个邮箱已经注册', 'This email is already registered');
      case 'invalid_verification_code':
        return _text('验证码无效或已经过期', 'The code is invalid or expired');
      case 'rate_limited':
        return _text('操作太频繁，请稍后再试', 'Too many attempts. Try again later.');
      case 'ACCOUNT_NOT_CONFIGURED':
        return _text('账号服务尚未配置', 'Account service is not configured');
      default:
        return error.message?.trim().isNotEmpty == true
            ? error.message!.trim()
            : _text('操作失败，请稍后重试', 'Operation failed. Try again later.');
    }
  }

  bool _looksLikeEmail(String value) {
    final at = value.indexOf('@');
    return at > 0 && value.indexOf('.', at) > at + 1;
  }

  void _showSuccessToast(String message) {
    showToast(message, type: ToastType.success);
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: _text('账号与 AI 服务', 'Account & AI service'),
        primary: true,
      ),
      body: Stack(
        children: [
          Positioned.fill(child: _buildBody()),
          if (_busy)
            Positioned.fill(
              child: ColoredBox(
                color: palette.overlayScrim,
                child: const Center(child: CircularProgressIndicator()),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    final session = _session;
    if (session == null) return _buildErrorState();
    if (!session.configured) return _buildNotConfigured();
    if (!session.signedIn || _overview == null) return _buildAuthForm();
    return _buildSignedIn(_overview!);
  }

  Widget _buildErrorState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(_error ?? _text('加载失败', 'Failed to load')),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: _loadAccount,
              child: Text(_text('重试', 'Retry')),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildNotConfigured() {
    return SafeArea(
      top: false,
      bottom: false,
      child: ListView(
        padding: edgeToEdgeScrollPadding(
          context,
          const EdgeInsets.fromLTRB(18, 10, 18, 28),
        ),
        children: [
          SettingsSectionTitle(label: _text('账号', 'Account')),
          Padding(
            padding: const EdgeInsets.fromLTRB(4, 4, 4, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  LucideIcons.cloudOff,
                  size: 28,
                  color: context.omniPalette.textSecondary,
                ),
                const SizedBox(height: 14),
                Text(
                  _text('账号服务尚未配置', 'Account service is not configured'),
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                const SizedBox(height: 8),
                Text(
                  _text(
                    '当前安装包没有设置 OMNIBOT_BASE_URL。配置品牌域名并重新构建后即可登录。',
                    'This build has no OMNIBOT_BASE_URL. Configure the public service domain and rebuild.',
                  ),
                  style: TextStyle(color: context.omniPalette.textSecondary),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildAuthForm() {
    return SafeArea(
      top: false,
      bottom: false,
      child: ListView(
        padding: edgeToEdgeScrollPadding(
          context,
          const EdgeInsets.fromLTRB(18, 10, 18, 28),
        ),
        children: [
          Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SettingsSectionTitle(
                  label: _registerMode
                      ? _text('创建小万账号', 'Create your account')
                      : _text('登录小万账号', 'Sign in to OmniBot'),
                  subtitle: _text(
                    '账号用于同步登录状态、平台额度和 AI 来源选择。',
                    'Your account syncs sessions, platform quota, and AI source choice.',
                  ),
                  bottomPadding: 16,
                ),
                _authModeSelector(),
                const SizedBox(height: 20),
                TextFormField(
                  controller: _emailController,
                  keyboardType: TextInputType.emailAddress,
                  autofillHints: const [AutofillHints.email],
                  decoration: InputDecoration(
                    labelText: _text('邮箱', 'Email'),
                    prefixIcon: const Icon(LucideIcons.mail, size: 20),
                  ),
                  validator: (value) => _looksLikeEmail(value?.trim() ?? '')
                      ? null
                      : _text('请输入正确的邮箱', 'Enter a valid email'),
                ),
                const SizedBox(height: 14),
                TextFormField(
                  controller: _passwordController,
                  obscureText: !_showPassword,
                  autofillHints: _registerMode
                      ? const [AutofillHints.newPassword]
                      : const [AutofillHints.password],
                  decoration: InputDecoration(
                    labelText: _text('密码', 'Password'),
                    helperText: _registerMode
                        ? _text('至少 15 个字符', 'At least 15 characters')
                        : null,
                    prefixIcon: const Icon(LucideIcons.lockKeyhole, size: 20),
                    suffixIcon: IconButton(
                      onPressed: () =>
                          setState(() => _showPassword = !_showPassword),
                      icon: Icon(
                        _showPassword ? LucideIcons.eyeOff : LucideIcons.eye,
                        size: 20,
                      ),
                    ),
                  ),
                  validator: (value) {
                    if ((value ?? '').isEmpty) {
                      return _text('请输入密码', 'Enter your password');
                    }
                    if (_registerMode && value!.characters.length < 15) {
                      return _text(
                        '密码至少需要 15 个字符',
                        'Use at least 15 characters',
                      );
                    }
                    return null;
                  },
                ),
                if (_registerMode) ...[
                  const SizedBox(height: 14),
                  TextFormField(
                    controller: _confirmPasswordController,
                    obscureText: !_showPassword,
                    autofillHints: const [AutofillHints.newPassword],
                    decoration: InputDecoration(
                      labelText: _text('确认密码', 'Confirm password'),
                      prefixIcon: const Icon(
                        LucideIcons.rotateCcwKey,
                        size: 20,
                      ),
                    ),
                    validator: (value) => value == _passwordController.text
                        ? null
                        : _text('两次密码不一致', 'Passwords do not match'),
                  ),
                  const SizedBox(height: 14),
                  TextFormField(
                    controller: _verificationCodeController,
                    keyboardType: TextInputType.number,
                    maxLength: 6,
                    decoration: InputDecoration(
                      labelText: _text('邮箱验证码', 'Email verification code'),
                      counterText: '',
                      prefixIcon: const Icon(LucideIcons.mailCheck, size: 20),
                      suffixIcon: TextButton(
                        onPressed: _busy ? null : _sendVerificationCode,
                        child: Text(
                          _codeRequest == null
                              ? _text('发送', 'Send')
                              : _text('重新发送', 'Resend'),
                        ),
                      ),
                    ),
                    validator: (value) => (value ?? '').trim().length == 6
                        ? null
                        : _text('请输入 6 位验证码', 'Enter the 6-digit code'),
                  ),
                ],
                if (_error != null) ...[
                  const SizedBox(height: 14),
                  _errorBanner(_error!),
                ],
                const SizedBox(height: 22),
                FilledButton(
                  onPressed: _busy ? null : _submitAuth,
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(48),
                  ),
                  child: Text(
                    _registerMode
                        ? _text('注册并登录', 'Create account & sign in')
                        : _text('登录', 'Sign in'),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _authModeSelector() {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: palette.segmentTrack,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          _authModeButton(_text('登录', 'Sign in'), false),
          _authModeButton(_text('注册', 'Register'), true),
        ],
      ),
    );
  }

  Widget _authModeButton(String label, bool register) {
    final selected = _registerMode == register;
    return Expanded(
      child: InkWell(
        onTap: () => setState(() {
          _registerMode = register;
          _error = null;
        }),
        borderRadius: BorderRadius.circular(9),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          padding: const EdgeInsets.symmetric(vertical: 10),
          decoration: BoxDecoration(
            color: selected
                ? context.omniPalette.segmentThumb
                : Colors.transparent,
            borderRadius: BorderRadius.circular(9),
          ),
          child: Text(
            label,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: selected
                  ? context.omniPalette.textPrimary
                  : context.omniPalette.textSecondary,
              fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSignedIn(AccountOverview overview) {
    final settings = overview.settings;
    final quotaSubtitle = !settings.platformAvailable
        ? _text(
            settings.platformUnavailableReason ?? '平台 AI 服务暂未开放，额度将在开放后使用',
            'Platform AI is not available yet; quota can be used after launch',
          )
        : settings.platform.enabled
        ? _text('可用于平台提供的 AI 服务', 'Available for the platform AI service')
        : _text('平台额度当前未启用', 'Platform quota is currently disabled');
    return SafeArea(
      top: false,
      bottom: false,
      child: RefreshIndicator(
        onRefresh: _loadAccount,
        child: ListView(
          padding: edgeToEdgeScrollPadding(
            context,
            const EdgeInsets.fromLTRB(18, 10, 18, 28),
          ),
          children: [
            SettingsSectionTitle(label: _text('账号', 'Account')),
            _summaryRow(
              icon: LucideIcons.userRound,
              title: overview.user.email,
              subtitle: _text(
                '已验证 · 当前设备已登录',
                'Verified · signed in on this device',
              ),
            ),
            _sectionDivider(),
            _summaryRow(
              icon: LucideIcons.coins,
              title: _text('平台额度', 'Platform quota'),
              subtitle: quotaSubtitle,
              trailing: settings.platformAvailable
                  ? Text(
                      '${settings.platform.balance}',
                      style: TextStyle(
                        color: context.omniPalette.accentPrimary,
                        fontSize: 20,
                        fontWeight: FontWeight.w700,
                      ),
                    )
                  : null,
            ),
            const SizedBox(height: 24),
            SettingsSectionTitle(label: _text('AI 来源', 'AI source')),
            _modeOption(
              mode: AiAccessMode.platform,
              selected:
                  settings.platformAvailable &&
                  settings.mode == AiAccessMode.platform,
              enabled: settings.platformAvailable,
              icon: LucideIcons.cloud,
              title: _text('使用平台额度', 'Use platform quota'),
              subtitle: settings.platformAvailable
                  ? _text(
                      '由小万平台统一提供模型服务，不显示内部 API 站。',
                      'Use OmniBot-managed models without exposing the internal API service.',
                    )
                  : _text(
                      settings.platformUnavailableReason ??
                          '平台 AI 服务暂未开放，后续可由服务器开启。',
                      'Platform AI is not available yet and can be enabled later by the server.',
                    ),
            ),
            _sectionDivider(),
            _modeOption(
              mode: AiAccessMode.byok,
              selected: settings.mode == AiAccessMode.byok,
              icon: LucideIcons.keyRound,
              title: _text('使用自己的 API Key', 'Use my own API key'),
              subtitle: _text(
                'Key 只保存在当前设备，不会上传账号服务器。',
                'Your key stays on this device and is never uploaded to the account server.',
              ),
            ),
            if (settings.mode == AiAccessMode.byok) ...[
              _sectionDivider(left: 34),
              _apiKeyAction(),
            ],
            if (_error != null) ...[
              const SizedBox(height: 14),
              _errorBanner(_error!),
            ],
            const SizedBox(height: 24),
            TextButton.icon(
              onPressed: _busy ? null : _logout,
              style: TextButton.styleFrom(
                minimumSize: const Size.fromHeight(46),
                foregroundColor: Theme.of(context).colorScheme.error,
              ),
              icon: const Icon(LucideIcons.logOut, size: 18),
              label: Text(_text('退出当前设备', 'Sign out on this device')),
            ),
          ],
        ),
      ),
    );
  }

  Widget _summaryRow({
    required IconData icon,
    required String title,
    required String subtitle,
    Widget? trailing,
  }) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 14, 2, 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Icon(icon, size: 20, color: palette.textPrimary),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w500,
                    color: palette.textPrimary,
                    height: 1.5,
                    fontFamily: 'PingFang SC',
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  subtitle,
                  style: TextStyle(
                    color: palette.textSecondary,
                    fontSize: 11,
                    fontWeight: FontWeight.w400,
                    height: 1.55,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              ],
            ),
          ),
          if (trailing != null) ...[const SizedBox(width: 12), trailing],
        ],
      ),
    );
  }

  Widget _sectionDivider({double left = 34}) {
    return Padding(
      padding: EdgeInsets.only(left: left),
      child: Divider(
        height: 1,
        thickness: 1,
        color: context.omniPalette.borderSubtle.withValues(
          alpha: context.isDarkTheme ? 0.5 : 0.78,
        ),
      ),
    );
  }

  Widget _apiKeyAction() {
    final palette = context.omniPalette;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () => GoRouterManager.push('/home/model_provider_setting'),
        borderRadius: BorderRadius.circular(14),
        splashColor: palette.accentPrimary.withValues(alpha: 0.08),
        highlightColor: Colors.transparent,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(4, 13, 2, 13),
          child: Row(
            children: [
              Icon(LucideIcons.settings, size: 18, color: palette.textPrimary),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  _text('配置我的 API Key', 'Configure my API key'),
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
              Icon(
                LucideIcons.chevronRight,
                size: 18,
                color: palette.textTertiary,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _modeOption({
    required AiAccessMode mode,
    required bool selected,
    required IconData icon,
    required String title,
    required String subtitle,
    bool enabled = true,
  }) {
    final palette = context.omniPalette;
    return Semantics(
      button: true,
      selected: selected,
      enabled: enabled,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: _busy || !enabled ? null : () => _changeMode(mode),
          borderRadius: BorderRadius.circular(14),
          splashColor: palette.accentPrimary.withValues(alpha: 0.08),
          highlightColor: Colors.transparent,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 180),
            padding: const EdgeInsets.fromLTRB(4, 14, 2, 14),
            decoration: BoxDecoration(
              color: selected
                  ? palette.accentPrimary.withValues(alpha: 0.07)
                  : Colors.transparent,
              borderRadius: BorderRadius.circular(14),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  icon,
                  size: 20,
                  color: !enabled
                      ? palette.textTertiary
                      : selected
                      ? palette.accentPrimary
                      : palette.textPrimary,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: TextStyle(
                          color: enabled
                              ? palette.textPrimary
                              : palette.textTertiary,
                          fontSize: 14,
                          fontWeight: FontWeight.w500,
                          height: 1.5,
                          fontFamily: 'PingFang SC',
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        subtitle,
                        style: TextStyle(
                          color: enabled
                              ? palette.textSecondary
                              : palette.textTertiary,
                          fontSize: 11,
                          fontWeight: FontWeight.w400,
                          height: 1.55,
                          fontFamily: 'PingFang SC',
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 12),
                Icon(
                  selected ? LucideIcons.circleCheck : LucideIcons.circle,
                  size: 19,
                  color: enabled && selected
                      ? palette.accentPrimary
                      : palette.textTertiary,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _errorBanner(String message) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.red.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          const Icon(LucideIcons.circleAlert, color: Colors.red, size: 20),
          const SizedBox(width: 8),
          Expanded(
            child: Text(message, style: const TextStyle(color: Colors.red)),
          ),
        ],
      ),
    );
  }
}
