// TradingSim.java — simple Java backtester (SMA crossover) with P&L, CAGR, Sharpe, Max DD.
import java.io.*; import java.nio.file.*; import java.time.*; import java.time.format.DateTimeFormatter;
import java.util.*; public class TradingSim {

    static class Bar { final LocalDate date; final double open,high,low,close; final long volume;
        Bar(LocalDate d,double o,double h,double l,double c,long v){date=d;open=o;high=h;low=l;close=c;volume=v;} }

    interface Strategy { int signal(List<Bar> h,int i); }

    static class SmaCross implements Strategy {
        final int fast, slow; SmaCross(int f,int s){fast=f;slow=s;}
        public int signal(List<Bar> h,int i){ if(i<slow) return 0;
            double f=sma(h,i,fast), s=sma(h,i,slow), pf=sma(h,i-1,fast), ps=sma(h,i-1,slow);
            if(f> s && pf<=ps) return +1; // buy
            if(f< s && pf>=ps) return -1; // sell
            return 0; }
        private double sma(List<Bar> h,int i,int w){ double sum=0; for(int k=i-w+1;k<=i;k++) sum+=h.get(k).close; return sum/w; }
    }

    static class Portfolio {
        double cash; int position; double avgEntry; final double commission; final double slippageBps;
        final List<Double> equity=new ArrayList<>(); final List<LocalDate> dates=new ArrayList<>(); final List<String> trades=new ArrayList<>();
        Portfolio(double start,double comm,double slp){cash=start;commission=comm;slippageBps=slp;}
        double eq(double px){return cash+position*px;}
        void mark(LocalDate d,double px){dates.add(d); equity.add(eq(px));}
        void buy(Bar next,int shares){ if(shares<=0) return; double fill=next.open*(1+slippageBps/10000.0);
            double cost=shares*fill+commission; if(cost>cash) return; double prev=position*avgEntry; position+=shares;
            avgEntry = position==0?0:(prev+shares*fill)/position; cash-=cost; trades.add(next.date+" BUY  "+shares+" @ "+r(fill)); }
        void sell(Bar next,int shares){ if(shares<=0||position<=0) return; shares=Math.min(shares,position);
            double fill=next.open*(1-slippageBps/10000.0); double proceeds=shares*fill-commission; position-=shares; cash+=proceeds;
            trades.add(next.date+" SELL "+shares+" @ "+r(fill)); if(position==0) avgEntry=0; }
    }

    static class Backtester {
        final List<Bar> bars; final Strategy strat; final Portfolio pf; final double riskFrac;
        Backtester(List<Bar> b,Strategy s,Portfolio p,double rf){bars=b;strat=s;pf=p;riskFrac=rf;}
        void run(){ for(int i=0;i<bars.size();i++){ Bar b=bars.get(i); pf.mark(b.date,b.close);
                int sig=strat.signal(bars,i); if(sig==0) continue; if(i+1>=bars.size()) break; Bar next=bars.get(i+1);
                if(sig>0 && pf.position==0){ int sh=(int)Math.floor((pf.eq(b.close)*riskFrac)/(next.open*(1+pf.slippageBps/10000.0))); pf.buy(next,sh); }
                else if(sig<0 && pf.position>0){ pf.sell(next,pf.position); } }
            if(pf.position>0 && !bars.isEmpty()){ Bar last=bars.get(bars.size()-1); pf.sell(last,pf.position); pf.mark(last.date,last.close); } }
    }

    static class Metrics {
        static double totalReturn(List<Double> e){ if(e.isEmpty()) return 0; return e.get(e.size()-1)/e.get(0)-1; }
        static double cagr(List<Double> e,List<LocalDate> d){ if(e.size()<2) return 0; double yrs=(d.get(d.size()-1).toEpochDay()-d.get(0).toEpochDay())/365.25;
            if(yrs<=0) return 0; return Math.pow(e.get(e.size()-1)/e.get(0),1.0/yrs)-1; }
        static double sharpe(List<Double> e){ if(e.size()<2) return 0; ArrayList<Double> r=new ArrayList<>();
            for(int i=1;i<e.size();i++) r.add((e.get(i)-e.get(i-1))/e.get(i-1));
            double mean=r.stream().mapToDouble(x->x).average().orElse(0); double var=0; for(double x:r) var+=(x-mean)*(x-mean);
            var/= (r.size()>1? r.size()-1:1); double sd=Math.sqrt(var); if(sd==0) return 0; return (mean/sd)*Math.sqrt(252.0); }
        static double maxDD(List<Double> e){ double peak=-1e100, mdd=0; for(double v:e){ if(v>peak) peak=v; double dd=(peak-v)/peak; if(dd>mdd) mdd=dd; } return mdd; }
    }

    static List<Bar> loadCsv(String path) throws IOException {
        List<String> lines=Files.readAllLines(Paths.get(path)); if(lines.isEmpty()) throw new IOException("Empty CSV");
        int start=0; if(lines.get(0).toLowerCase().contains("date")) start=1;
        DateTimeFormatter[] fmts={DateTimeFormatter.ofPattern("yyyy-MM-dd"), DateTimeFormatter.ofPattern("M/d/yyyy"), DateTimeFormatter.ofPattern("M/d/yy")};
        List<Bar> out=new ArrayList<>(); for(int i=start;i<lines.size();i++){ String[] t=lines.get(i).trim().split(",",-1); if(t.length<6) continue;
            LocalDate d=null; for(DateTimeFormatter f:fmts){ try{ d=LocalDate.parse(t[0].trim(),f); break; }catch(Exception ignore){} } if(d==null) continue;
            double o=p(t[1]), h=p(t[2]), l=p(t[3]), c=p(t[4]); long v=(long)p(t[5]); out.add(new Bar(d,o,h,l,c,v)); } return out; }
    static double p(String s){ return Double.parseDouble(s.trim()); }
    static String r(double x){ return String.format(Locale.US,"%.4f",x); }

    public static void main(String[] a) throws Exception{
        if(a.length<1){ System.out.println("Usage: java TradingSim <path_to_csv>\nCSV: Date,Open,High,Low,Close,Volume"); return; }
        List<Bar> bars=loadCsv(a[0]); if(bars.size()<200) System.out.println("Loaded "+bars.size()+" bars. (200+ better for SMA200).");
        Strategy strat=new SmaCross(50,200);
        Portfolio pf=new Portfolio(100_000.0, 0.50, 5.0); // $100k, $0.50 commission, 5 bps slippage
        Backtester bt=new Backtester(bars,strat,pf,1.00); bt.run();
        double tot=Metrics.totalReturn(pf.equity), cagr=Metrics.cagr(pf.equity,pf.dates),
               shp=Metrics.sharpe(pf.equity), mdd=Metrics.maxDD(pf.equity);
        System.out.println("=== Results (SMA 50/200, long-only) ===");
        System.out.println("Bars: "+bars.size()); System.out.println("Trades: "+pf.trades.size());
        System.out.println("Final Equity: $"+r(pf.equity.get(pf.equity.size()-1)));
        System.out.println("Total Return: "+r(tot*100)+"%"); System.out.println("CAGR: "+r(cagr*100)+"%");
        System.out.println("Sharpe (ann.): "+r(shp)); System.out.println("Max Drawdown: "+r(mdd*100)+"%");
        System.out.println("\nTrade Log:"); pf.trades.forEach(System.out::println);
    }
}
