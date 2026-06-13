namespace TabMonitor;

/// <summary>
/// CMD 진입점. 아래 명령어를 지원합니다.
///
///   TabMonitor add               기본 해상도(2560x1600 @60Hz)로 가상 모니터 추가
///   TabMonitor add 1920 1080     해상도 지정해서 추가
///   TabMonitor add 1920 1080 60  해상도 + 주사율 지정
///   TabMonitor remove            가상 모니터 제거 (프로세스 종료)
///   TabMonitor status            현재 연결된 모니터 목록 출력
///
/// add 명령은 Enter 또는 Ctrl+C로 종료할 때까지 프로세스를 유지합니다.
/// 프로세스가 살아있는 동안 PingWorker가 드라이버에 ping을 보내
/// 가상 모니터 연결을 유지합니다.
/// </summary>
class Program
{
    static int Main(string[] args)
    {
        if (args.Length == 0)
        {
            PrintUsage();
            return 1;
        }

        return args[0].ToLower() switch
        {
            "add"    => CmdAdd(args),
            "remove" => CmdRemove(),
            "status" => CmdStatus(),
            _        => (PrintUsage(), 1).Item2
        };
    }

    // ──────────────────────────────────────────────
    // add: 가상 모니터 추가 후 ping 루프로 유지
    // ──────────────────────────────────────────────
    static int CmdAdd(string[] args)
    {
        int width  = args.Length > 1 ? int.Parse(args[1]) : 1920;
        int height = args.Length > 2 ? int.Parse(args[2]) : 1080;
        int hz     = args.Length > 3 ? int.Parse(args[3]) : 60;

        Console.WriteLine("=== TabMonitor — 가상 모니터 추가 ===");
        Console.WriteLine($"목표 해상도: {width}x{height} @ {hz}Hz");
        Console.WriteLine();

        // 1. 레지스트리에 해상도 프리셋 등록
        DisplayHelper.RegisterVddResolution(width, height, hz);

        using var vdd = new VddController();
        if (!vdd.Open()) return 1;

        // 2. 드라이버 재연결로 프리셋 반영
        Console.WriteLine("[VDD] 프리셋 로드를 위한 초기 연결...");
        vdd.AddDisplay();
        Thread.Sleep(500);
        vdd.RemoveDisplay(0);
        Thread.Sleep(1000);

        // 3. 재연결
        Console.WriteLine("[VDD] 재연결 중...");
        int displayIndex = vdd.AddDisplay();

        var pinger = new PingWorker(vdd);
        pinger.Start();

        // 4. 가상 모니터 설정
        var monitor = DisplayHelper.FindVirtualMonitor();
        if (monitor == null)
        {
            Console.WriteLine("[오류] 가상 모니터를 찾을 수 없습니다.");
            pinger.Stop();
            return 1;
        }

        Console.WriteLine($"✓ 가상 모니터 발견: {monitor.DeviceName}");
        DisplayHelper.SetResolution(monitor.DeviceName, width, height, hz);
        DisplayHelper.PlaceRightOf(monitor.DeviceName);
        DisplayHelper.ApplyChanges();
        Console.WriteLine($"✓ 준비 완료: {monitor.DeviceName} ({width}x{height} @ {hz}Hz)");
        //Console.WriteLine();

        // 5. ADB USB 터널 설정
        Console.WriteLine($"[테스트] ADB 터널 초기화 전");
        bool adbOk = AdbHelper.SetupReverse();
        Console.WriteLine($"[테스트] ADB 터널 초기화 완료");
        if (!adbOk)
        {
            Console.WriteLine("[경고] ADB 없이 계속합니다 (Wi-Fi로 연결하거나 나중에 설정 가능)");
        }

        // 6. 가상 모니터 인덱스 찾기 (DXGI 모니터 순서 기준)
        //    가상 모니터는 보통 마지막 인덱스
        var monitors    = DisplayHelper.GetMonitors();
        Console.WriteLine($"[테스트] 모니터 찾기 초기화 완료");
        int virtIndex   = monitors.FindIndex(m => m.IsVirtual);
        if (virtIndex < 0) virtIndex = monitors.Count - 1; // 못 찾으면 마지막

        // 7. 화면 캡처 + TCP 서버 시작
        ScreenCapture? capture = null;
        FrameServer?   server  = null;

        try
        {
            //capture = new ScreenCapture(monitor.DeviceName, width, height);
            capture = new ScreenCapture(monitor.DeviceName, width, height);

            Console.WriteLine($"[테스트] 캡처 초기화 완료");
            server  = new FrameServer(capture, port: 7070);
            Console.WriteLine($"[테스트] 서버 초기화 완료");
            server.Start();
            Console.WriteLine($"[테스트] 서버 시작 완료");
            
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[경고] 캡처 초기화 실패: {ex.Message}");
            Console.WriteLine("       가상 모니터는 유지되지만 화면 전송은 안 됩니다.");
        }

        Console.WriteLine();
        Console.WriteLine("종료하려면 Enter 또는 Ctrl+C 를 누르세요...");

        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            Console.WriteLine("\n[종료 신호 수신]");
        };

        Console.ReadLine();

        // 8. 정리
        server?.Stop();
        capture?.Dispose();
        AdbHelper.RemoveReverse();
        pinger.Stop();
        vdd.RemoveDisplay(displayIndex);
        Console.WriteLine("✓ 종료 완료");
        return 0;
    }

    // ──────────────────────────────────────────────
    // remove: 실행 중인 가상 모니터를 제거
    // (현재 프로세스를 통하지 않고 직접 드라이버에 제거 신호)
    // ──────────────────────────────────────────────
    static int CmdRemove()
    {
        Console.WriteLine("=== TabMonitor — 가상 모니터 제거 ===");

        using var vdd = new VddController();
        if (!vdd.Open())
            return 1;

        // index 0번 제거 (단일 가상 모니터 가정)
        vdd.RemoveDisplay(0);
        Console.WriteLine("✓ 완료");
        return 0;
    }

    // ──────────────────────────────────────────────
    // status: 현재 연결된 모니터 목록 출력
    // ──────────────────────────────────────────────
    static int CmdStatus()
    {
        Console.WriteLine("=== 현재 연결된 모니터 ===");
        Console.WriteLine();

        var monitors = DisplayHelper.GetMonitors();

        if (monitors.Count == 0)
        {
            Console.WriteLine("  (모니터 없음)");
            return 0;
        }

        foreach (var m in monitors)
        {
            string tag = m.IsVirtual ? " [가상/Parsec VDD]" : "";
            Console.WriteLine($"  {m.DeviceName}{tag}");
            Console.WriteLine($"    설명    : {m.Description}");
            Console.WriteLine($"    해상도  : {m.Width}x{m.Height} @ {m.Hz}Hz");
            Console.WriteLine($"    위치    : X={m.PosX}, Y={m.PosY}");
            Console.WriteLine();
        }

        return 0;
    }

    static (object, int) PrintUsage()
    {
        Console.WriteLine("사용법:");
        Console.WriteLine("  TabMonitor add [width height [hz]]   가상 모니터 추가");
        Console.WriteLine("  TabMonitor remove                    가상 모니터 제거");
        Console.WriteLine("  TabMonitor status                    모니터 목록 출력");
        Console.WriteLine();
        Console.WriteLine("예시:");
        Console.WriteLine("  TabMonitor add                       2560x1600 @ 60Hz");
        Console.WriteLine("  TabMonitor add 1920 1080             1920x1080 @ 60Hz");
        Console.WriteLine("  TabMonitor add 1920 1080 120         1920x1080 @ 120Hz");
        return (new object(), 1);
    }
}