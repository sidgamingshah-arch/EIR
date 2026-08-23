from decimal import Decimal as D, getcontext
getcontext().prec = 40
from eir_ref import amort_table, q, emi, solve_irr, npv, annualise, pow_d

P=D('1000000'); i=D('0.12')/12; n=24
E=q(emi(P,i,n)); ca0=D('995000')
flows=[(t,E) for t in range(1,n+1)]
eir=solve_irr(flows,ca0)
rowsE=amort_table(ca0,eir,{t:E for t in range(1,n+1)},n)
rowsC=amort_table(P,i,{t:E for t in range(1,n+1)},n)

print("="*76); print("CASE 5 (REWORK) - Stage 3 under ACPIR: three-way decomposition"); print("="*76)
gca=rowsE[11]['closing']; ecl=q(gca*D('0.40')); net=gca-ecl
gross_int = gca*eir; net_int = net*eir; unwind = ecl*eir
print(f"Gross carrying amount, m13 opening        : {q(gca)}")
print(f"Lifetime ECL allowance (40% of GCA)       : {q(ecl)}")
print(f"Amortised cost (net)                      : {q(net)}")
print(f"EIR (unchanged by staging)                : {q(eir,10)}")
print()
print(f"(a) Gross-basis interest  GCA x EIR       : {q(gross_int)}   <- Stage 1/2 amount")
print(f"(b) IFRS 9 Stage 3        AC  x EIR       : {q(net_int)}   <- net basis, IFRS 9 5.4.1")
print(f"(c) ECL discount unwind   ECL x EIR       : {q(unwind)}   <- mechanical, ACPIR 50")
print(f"    identity check  (b)+(c) = (a)         : {q(net_int+unwind)} == {q(gross_int)}  -> {net_int+unwind==gross_int}")
print()
print(f"ACPIR 2026 recognised in P&L              : 0.00   <- income NOT recognised on Stage 3")
print(f"To interest-in-suspense (contractual)     : {q(rowsC[12]['interest'])}")
print(f"Shadow unwind retained for ECL roll-fwd   : {q(unwind)}")

print(); print("="*76); print("CASE 8 - IFRS 9 B5.4.4 next-repricing-date shortcut"); print("="*76)
bal12=rowsC[11]['closing']
print(f"Floating EBLR loan, 24m, resets at m12. Net integral fee 5,000.")
print(f"Contractual balance at first reset (m12)  : {q(bal12)}")
print()
print("(i) Full-expected-life amortisation (fee spread over all 24 months)")
print(f"    EIR                                   : {q(eir*100,8)}% /mo, {q(annualise(eir)*100,6)}% p.a.")
fee_y1_full = sum(rowsE[k]['interest']-rowsC[k]['interest'] for k in range(12))
print(f"    Net fee amortised in months 1-12      : {q(fee_y1_full)}")
print(f"    Unamortised fee carried past reset    : {q(D('5000')-fee_y1_full)}")
print()
print("(ii) B5.4.4 shortcut: amortise to the next repricing date")
sc=[(t,E) for t in range(1,13)]+[(12,bal12)]
eir_sc=solve_irr(sc,ca0)
print(f"    Flows: 12 EMIs + notional redemption of {q(bal12)} at m12")
print(f"    EIR to next reset                     : {q(eir_sc*100,8)}% /mo, {q(annualise(eir_sc)*100,6)}% p.a.")
rs=amort_table(ca0,eir_sc,{t:(E+bal12 if t==12 else E) for t in range(1,13)},12)
fee_y1_sc=sum(rs[k]['interest']-rowsC[k]['interest'] for k in range(12))
print(f"    Net fee amortised in months 1-12      : {q(fee_y1_sc)}")
print(f"    Unamortised fee carried past reset    : {q(D('5000')-fee_y1_sc)}")
print(f"    Terminal check (must be ~0)           : {q(rs[-1]['closing'],6)}")
print()
print(f"    Year-1 fee recognition, shortcut vs full life : {q(fee_y1_sc)} vs {q(fee_y1_full)}")
print(f"    Acceleration                                  : {q(fee_y1_sc-fee_y1_full)} "
      f"({q((fee_y1_sc/fee_y1_full-1)*100,1)}% more)")

print(); print("="*76); print("CASE 9 - Straight-line vs EIR on a long zero-coupon (the wedge)"); print("="*76)
face=D('1000000'); y=D('0.08'); yrs=15
price=face/pow_d(D(1)+y,yrs); disc=face-price
print(f"15-year zero-coupon bond, face {q(face)}, yield {q(y*100,2)}% p.a.")
print(f"Purchase price (PV)                       : {q(price)}")
print(f"Total discount to accrete                 : {q(disc)}")
print()
sl=disc/yrs
print(f"{'Yr':>3} {'SL accretion':>14} {'EIR accretion':>15} {'SL overstates by':>18}")
bal=price; cum_sl=D(0); cum_eir=D(0)
for t in range(1,yrs+1):
    ea=bal*y; bal+=ea; cum_sl+=sl; cum_eir+=ea
    if t in (1,2,3,5,10,14,15):
        print(f"{t:>3} {q(sl):>14} {q(ea):>15} {q(sl-ea):>18}")
print(f"{'TOT':>3} {q(cum_sl):>14} {q(cum_eir):>15} {q(cum_sl-cum_eir):>18}")
y1=price*y
print()
print(f"Year 1: straight-line {q(sl)} vs EIR {q(y1)}")
print(f"  -> straight-line overstates year-1 income by {q((sl/y1-1)*100,1)}%")
print(f"Year 15: straight-line {q(sl)} vs EIR {q(bal/(D(1)+y)*y)}")
print(f"  -> straight-line understates final-year income by {q((1-sl/(bal/(D(1)+y)*y))*100,1)}%")
print(f"Terminal carrying amount check            : {q(bal)} vs face {q(face)}")

print(); print("="*76); print("EXHIBIT - behavioural life leverage on fee amortisation"); print("="*76)
Pm=D('5000000'); im=D('0.09')/12; fee=D('50000'); ca=Pm-fee
for months in (240,96):
    Em=q(emi(Pm,im,months))
    fl=[(t,Em) for t in range(1,months+1)]
    r=solve_irr(fl,ca)
    re=amort_table(ca,r,{t:Em for t in range(1,months+1)},months)
    rc=amort_table(Pm,im,{t:Em for t in range(1,months+1)},months)
    y1=sum(re[k]['interest']-rc[k]['interest'] for k in range(12))
    print(f"assumed life {months:>3}m ({months//12:>2}y): EIR {q(annualise(r)*100,6)}% p.a., "
          f"year-1 fee recognised {q(y1)}")
