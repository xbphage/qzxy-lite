package com.qzxy.lite.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qzxy.lite.appContainer
import com.qzxy.lite.ui.common.ErrorNotice
import com.qzxy.lite.ui.common.UiState
import com.qzxy.lite.ui.theme.LocalQzColors
import com.qzxy.lite.ui.theme.SteamWaves

/**
 * 登录页（2026-11-13 视觉改版）：深潭英雄区（蒸汽波 + 水滴印记）在上方，
 * 下方表单层就是「账户密码登录」——密码是主路径；
 * 短信验证码收为切换路径（验证码被限流时用），两套登录链均为实测代码路径。
 */
@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    val context = LocalContext.current
    val vm: LoginViewModel = viewModel {
        LoginViewModel(context.appContainer.authRepository, context.appContainer.sessionStore)
    }
    val state by vm.state.collectAsState()
    val kickReason by context.appContainer.sessionStore.kickReason.collectAsState()
    val qz = LocalQzColors.current
    var showPassword by rememberSaveable { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .imePadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // —— 英雄区：深潭底 + 蒸汽波，唯一的响亮元素 ——
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(0.36f)
                    .background(qz.deep),
            ) {
                SteamWaves(Modifier.fillMaxSize(), color = qz.onDeep, baseAlpha = 0.20f)
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .statusBarsPadding()
                        .padding(start = 28.dp, end = 28.dp, bottom = 52.dp),
                ) {
                    Icon(
                        Icons.Rounded.WaterDrop,
                        contentDescription = null,
                        tint = qz.onDeep,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text("趣智 Lite", style = MaterialTheme.typography.headlineSmall, color = qz.onDeep)
                    Text(
                        "热水，一按就来",
                        style = MaterialTheme.typography.bodyLarge,
                        color = qz.onDeep.copy(alpha = 0.72f),
                    )
                }
            }

            // —— 表单层：向上叠进英雄区 28dp，圆角起始 ——
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(0.64f)
                    .offset(y = (-28).dp)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.surface)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = 30.dp, bottom = 20.dp),
            ) {
                Text(if (state.smsMode) "验证码登录" else "密码登录", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(6.dp))
                Text(
                    "登录后自动写入会话，仅用于本人账号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                kickReason?.let { reason ->
                    Spacer(Modifier.size(16.dp))
                    ErrorNotice(
                        "$reason\n原版 App 与 lite 共用同一账号会互相顶掉会话；" +
                            "请先「强行停止」原版趣智校园再重新登录。",
                    )
                }

                Spacer(Modifier.size(20.dp))

                OutlinedTextField(
                    value = state.telephone,
                    onValueChange = vm::onTelephoneChange,
                    label = { Text("手机号") },
                    leadingIcon = { Icon(Icons.Rounded.Smartphone, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = if (state.smsMode) ImeAction.Next else ImeAction.Done,
                    ),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(14.dp))

                if (!state.smsMode) {
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = vm::onPasswordChange,
                        label = { Text("密码") },
                        leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                                )
                            }
                        },
                        visualTransformation =
                            if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(22.dp))
                    Button(
                        onClick = { vm.loginWithPassword(onLoginSuccess) },
                        enabled = state.telephone.length == 11 &&
                            state.password.isNotBlank() &&
                            state.loginState !is UiState.Loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                    ) {
                        if (state.loginState is UiState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("登录", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = vm::onCodeChange,
                        label = { Text("短信验证码") },
                        leadingIcon = { Icon(Icons.Rounded.WaterDrop, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(10.dp))
                    OutlinedButton(
                        onClick = vm::sendCode,
                        enabled = state.telephone.length == 11 &&
                            state.countdown == 0 &&
                            state.sendCodeState !is UiState.Loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.countdown > 0) "重发（${state.countdown}s）" else "获取验证码")
                    }
                    when (val sc = state.sendCodeState) {
                        is UiState.Error -> {
                            Spacer(Modifier.size(6.dp))
                            Text(
                                sc.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        else -> {}
                    }
                    Spacer(Modifier.size(22.dp))
                    Button(
                        onClick = { vm.login(onLoginSuccess) },
                        enabled = state.telephone.length == 11 &&
                            state.code.length >= 4 &&
                            state.loginState !is UiState.Loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                    ) {
                        if (state.loginState is UiState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("验证码登录", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                when (val ls = state.loginState) {
                    is UiState.Error -> {
                        Spacer(Modifier.size(14.dp))
                        Text(
                            ls.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> {}
                }

                Spacer(Modifier.size(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = vm::toggleMode) {
                        Text(if (state.smsMode) "改用密码登录" else "改用短信验证码登录")
                    }
                }

                Spacer(Modifier.size(6.dp))
                Text(
                    "仅用于本人账号操作与学习。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 8.dp),
                )
            }
        }
    }
}
